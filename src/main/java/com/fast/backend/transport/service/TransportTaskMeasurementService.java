package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.storage.mapper.StorageSlotPlacementRow;
import com.fast.backend.storage.placement.PlacementCandidate;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.storage.placement.PlacementService;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.websocket.TransportTaskBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 최종 측정 결과를 측정 중인 운반 작업에 연결하고 적재 위치를 예약한다. */
@Service
public class TransportTaskMeasurementService {

    private static final Logger log = LoggerFactory.getLogger(TransportTaskMeasurementService.class);

    private final TransportTaskMapper taskMapper;
    private final StorageSlotMapper slotMapper;
    private final PlacementService placementService;
    private final StationMeasurementPlacementEligibility eligibility;
    private final TransportTaskBroadcaster broadcaster;

    public TransportTaskMeasurementService(
            TransportTaskMapper taskMapper,
            StorageSlotMapper slotMapper,
            PlacementService placementService,
            StationMeasurementPlacementEligibility eligibility,
            TransportTaskBroadcaster broadcaster) {
        this.taskMapper = taskMapper;
        this.slotMapper = slotMapper;
        this.placementService = placementService;
        this.eligibility = eligibility;
        this.broadcaster = broadcaster;
    }

    public void complete(StationSession session, StationMeasurement measurement) {
        TransportTask task = taskMapper.findByMeasurementSessionId(session.getSessionId()).orElse(null);
        if (task == null) {
            return;
        }
        if (!session.getCargoId().equals(task.getCargoId())) {
            throw new IllegalStateException("측정 세션과 운반 작업의 화물이 일치하지 않습니다: "
                    + session.getSessionId());
        }
        if (!eligibility.isEligible(measurement)) {
            fail(task, "측정 결과가 적재 조건을 만족하지 않음");
            return;
        }

        final PlacementRecommendation recommendation;
        try {
            recommendation = placementService.recommend(
                    measurement.getCargoHeight(), loadEmptyCandidates());
        } catch (BusinessException exception) {
            fail(task, exception.getMessage());
            return;
        }

        if (slotMapper.reserveIfEmpty(recommendation.slotCode(), task.getId()) != 1) {
            fail(task, "선택된 적재 위치를 예약하지 못함: " + recommendation.slotCode());
            return;
        }
        if (taskMapper.completeMeasurement(
                task.getId(), measurement.getMeasurementId(), recommendation.slotCode(),
                recommendation.destinationX(), recommendation.destinationY(),
                recommendation.destinationHeading(), recommendation.forkHeight()) != 1) {
            slotMapper.releaseReservation(recommendation.slotCode(), task.getId());
            throw new IllegalStateException("측정 결과를 운반 작업에 연결하지 못했습니다: " + task.getTaskCode());
        }
        broadcaster.broadcastAfterCommit(
                "TRANSPORT_TASK_MEASURED", task.getTaskCode(), TaskStatus.PICKING_UP.name(), task.getVehicleId());
        log.info("Transport task measurement completed: taskId={}, measurementId={}, slotCode={}",
                task.getTaskCode(), measurement.getMeasurementId(), recommendation.slotCode());
    }

    private List<PlacementCandidate> loadEmptyCandidates() {
        return slotMapper.findAllEmptySlotsForPlacement().stream()
                .map(this::toCandidate)
                .toList();
    }

    private PlacementCandidate toCandidate(StorageSlotPlacementRow row) {
        return new PlacementCandidate(
                row.getSlotCode(), row.getUsableHeight(), row.getForkHeight(),
                row.getDestinationX(), row.getDestinationY(), row.getDestinationHeading(),
                null, row.getStatus());
    }

    private void fail(TransportTask task, String reason) {
        taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MEASURING, TaskStatus.FAILED,
                null, LocalDateTime.now());
        broadcaster.broadcastAfterCommit(
                "TRANSPORT_TASK_FAILED", task.getTaskCode(), TaskStatus.FAILED.name(), task.getVehicleId());
        log.warn("Transport task failed after measurement: taskId={}, reason={}", task.getTaskCode(), reason);
    }
}
