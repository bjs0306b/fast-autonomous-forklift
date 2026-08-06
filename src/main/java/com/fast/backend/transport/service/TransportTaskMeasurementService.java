package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.storage.mapper.StorageSlotPlacementRow;
import com.fast.backend.storage.placement.PlacementCandidate;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.storage.placement.PlacementService;
import com.fast.backend.transport.domain.TaskFailureCode;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.websocket.TransportTaskBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final TransportMaterialTaskPublisher materialTaskPublisher;

    public TransportTaskMeasurementService(
            TransportTaskMapper taskMapper,
            StorageSlotMapper slotMapper,
            PlacementService placementService,
            StationMeasurementPlacementEligibility eligibility,
            TransportTaskBroadcaster broadcaster,
            TransportMaterialTaskPublisher materialTaskPublisher) {
        this.taskMapper = taskMapper;
        this.slotMapper = slotMapper;
        this.placementService = placementService;
        this.eligibility = eligibility;
        this.broadcaster = broadcaster;
        this.materialTaskPublisher = materialTaskPublisher;
    }

    /**
     * 측정 결과를 작업과 적재 위치에 연결한다.
     *
     * @return 작업이 정상적으로 {@link TaskStatus#PICKING_UP} 상태가 되어 다음 입하를 준비해도 되면
     *         {@code true}, 측정 부적합·추천 실패 등으로 작업을 실패 처리했으면 {@code false}
     */
    public boolean complete(StationSession session, StationMeasurement measurement) {
        TransportTask task = taskMapper.findByMeasurementSessionId(session.getSessionId()).orElse(null);
        if (task == null) {
            return false;
        }
        if (!session.getCargoId().equals(task.getCargoId())) {
            throw new IllegalStateException("측정 세션과 운반 작업의 화물이 일치하지 않습니다: "
                    + session.getSessionId());
        }
        if (!eligibility.isEligible(measurement)) {
            // 측정 상태가 실패 원인을 결정한다. status=ok 인데 여기 걸렸다면 남은 원인은 적재 부적합뿐이라
            // 재측정이 아니라 "화물을 다시 쌓아야 한다"는 안내로 갈라진다.
            fail(task, TaskFailureCode.fromMeasurement(measurement.getStatus()),
                    "측정 결과가 적재 조건을 만족하지 않음");
            return false;
        }

        final PlacementRecommendation recommendation;
        try {
            recommendation = placementService.recommend(
                    measurement.getCargoHeight(), measurement.getCargoWidth(), loadEmptyCandidates());
        } catch (BusinessException exception) {
            fail(task, TaskFailureCode.PLACEMENT_SLOT_UNAVAILABLE, exception.getMessage());
            return false;
        }

        if (slotMapper.reserveIfEmpty(recommendation.slotCode(), task.getId()) != 1) {
            fail(task, TaskFailureCode.PLACEMENT_SLOT_UNAVAILABLE,
                    "선택된 적재 위치를 예약하지 못함: " + recommendation.slotCode());
            return false;
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
        materialTaskPublisher.publishAfterCommit(
                task.getTaskCode(), task.getVehicleId(), task.getCargoId(),
                measurement.getCargoHeight(), recommendation);
        log.info("Transport task measurement completed: taskId={}, measurementId={}, slotCode={}",
                task.getTaskCode(), measurement.getMeasurementId(), recommendation.slotCode());
        return true;
    }

    private List<PlacementCandidate> loadEmptyCandidates() {
        return slotMapper.findAllEmptySlotsForPlacement().stream()
                .map(this::toCandidate)
                .toList();
    }

    private PlacementCandidate toCandidate(StorageSlotPlacementRow row) {
        return new PlacementCandidate(
                row.getSlotCode(), row.getUsableHeight(), row.getUsableWidth(), row.getForkHeight(),
                row.getDestinationX(), row.getDestinationY(), row.getDestinationHeading(),
                null, row.getStatus());
    }

    private void fail(TransportTask task, TaskFailureCode failureCode, String reason) {
        taskMapper.failWithCode(
                task.getId(), TaskStatus.MEASURING, CommunicationTime.nowLocal(), failureCode);
        broadcaster.broadcastAfterCommit(
                "TRANSPORT_TASK_FAILED", task.getTaskCode(), TaskStatus.FAILED.name(),
                task.getVehicleId(), failureCode);
        log.warn("Transport task failed after measurement: taskId={}, failureCode={}, reason={}",
                task.getTaskCode(), failureCode, reason);
    }
}
