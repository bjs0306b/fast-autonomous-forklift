package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.service.StationMeasurementPlacementEligibility;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.storage.mapper.StorageSlotPlacementRow;
import com.fast.backend.storage.placement.PlacementCandidate;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.storage.placement.PlacementService;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskListResponse;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 저장된 측정 결과를 바탕으로 높이 기반 운반 작업을 생성하고 상태를 전이한다. */
@Service
public class TransportTaskService {

    private final CargoMapper cargoMapper;
    private final StationMeasurementMapper stationMeasurementMapper;
    private final StationMeasurementPlacementEligibility placementEligibility;
    private final StorageSlotMapper storageSlotMapper;
    private final TransportTaskMapper transportTaskMapper;
    private final PlacementService placementService;
    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    public TransportTaskService(
            CargoMapper cargoMapper,
            StationMeasurementMapper stationMeasurementMapper,
            StationMeasurementPlacementEligibility placementEligibility,
            StorageSlotMapper storageSlotMapper,
            TransportTaskMapper transportTaskMapper,
            PlacementService placementService,
            VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper vehicleCurrentStatusMapper) {
        this.cargoMapper = cargoMapper;
        this.stationMeasurementMapper = stationMeasurementMapper;
        this.placementEligibility = placementEligibility;
        this.storageSlotMapper = storageSlotMapper;
        this.transportTaskMapper = transportTaskMapper;
        this.placementService = placementService;
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
    }

    @Transactional
    public TransportTaskResponse createTask(TransportTaskCreateRequest request) {
        String cargoId = request.cargoId();
        if (!cargoMapper.existsByCargoId(cargoId)) {
            throw new BusinessException(ErrorCode.CARGO_NOT_FOUND, "등록되지 않은 화물입니다: " + cargoId);
        }
        if (transportTaskMapper.existsOpenTaskByCargoId(cargoId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 진행 중인 운반 작업이 있습니다: " + cargoId);
        }

        StationMeasurement measurement = stationMeasurementMapper.findLatestByCargoId(cargoId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.STATION_MEASUREMENT_NOT_FOUND, "화물의 측정 결과가 없습니다: " + cargoId));
        placementEligibility.requireEligible(measurement);

        PlacementRecommendation recommendation = placementService.recommend(
                measurement.getCargoHeight(), loadEmptyCandidates());

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-" + UUID.randomUUID());
        task.setCargoId(cargoId);
        task.setMeasurementId(measurement.getMeasurementId());
        task.setDestinationSlotCode(recommendation.slotCode());
        task.setDestinationX(recommendation.destinationX());
        task.setDestinationY(recommendation.destinationY());
        task.setDestinationHeading(recommendation.destinationHeading());
        task.setForkHeight(recommendation.forkHeight());
        task.setStatus(TaskStatus.PENDING);
        task.setCreatedAt(LocalDateTime.now());
        transportTaskMapper.insert(task);

        if (storageSlotMapper.reserveIfEmpty(recommendation.slotCode(), task.getId()) != 1) {
            throw new BusinessException(ErrorCode.STORAGE_SLOT_ALREADY_RESERVED,
                    "다른 작업이 먼저 적재 위치를 예약했습니다: " + recommendation.slotCode());
        }
        return TransportTaskResponse.from(task);
    }

    @Transactional
    public TransportTaskResponse assign(String taskCode, String vehicleId) {
        TransportTask task = getTaskOrThrow(taskCode);
        if (task.getStatus() != TaskStatus.PENDING || task.getVehicleId() != null) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_ASSIGNED);
        }
        Vehicle vehicle = vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND));
        if (!vehicle.isActive()) {
            throw new BusinessException(ErrorCode.VEHICLE_INACTIVE);
        }
        requireIdleVehicle(vehicleId);
        if (transportTaskMapper.existsActiveTaskByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_ALREADY_ASSIGNED);
        }
        if (transportTaskMapper.updateAssignment(taskCode, vehicleId, LocalDateTime.now()) != 1) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_ASSIGNED);
        }
        return getDetail(taskCode);
    }

    @Transactional(readOnly = true)
    public TransportTaskResponse getDetail(String taskCode) {
        return TransportTaskResponse.from(getTaskOrThrow(taskCode));
    }

    @Transactional(readOnly = true)
    public TransportTaskListResponse list(
            int page, int size, TaskStatus status, String vehicleId, String cargoId) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int offset = safePage * safeSize;
        List<TransportTaskResponse> items = transportTaskMapper
                .findAll(status, vehicleId, cargoId, safeSize, offset)
                .stream().map(TransportTaskResponse::from).toList();
        long total = transportTaskMapper.countAll(status, vehicleId, cargoId);
        return new TransportTaskListResponse(items, safePage, safeSize, total);
    }

    @Transactional
    public TransportTaskResponse changeStatus(String taskCode, String rawStatus) {
        TransportTask task = getTaskOrThrow(taskCode);
        TaskStatus target = parseStatus(rawStatus);
        TaskStatus.validateTransition(task.getStatus(), target);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startedAt = target == TaskStatus.MOVING_TO_PICKUP ? now : null;
        LocalDateTime pickedUpAt = target == TaskStatus.PICKING_UP ? now : null;
        LocalDateTime completedAt = target == TaskStatus.COMPLETED ? now : null;
        LocalDateTime failedAt = target == TaskStatus.FAILED ? now : null;

        if (target == TaskStatus.ASSIGNED && task.getVehicleId() == null) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE, "차량이 배정되지 않은 작업입니다.");
        }
        if (target == TaskStatus.COMPLETED
                && storageSlotMapper.markOccupied(
                        task.getDestinationSlotCode(), task.getId(), task.getCargoId()) != 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "예약된 적재 위치를 점유 처리하지 못했습니다.");
        }
        if ((target == TaskStatus.FAILED || target == TaskStatus.CANCELLED)
                && storageSlotMapper.releaseReservation(task.getDestinationSlotCode(), task.getId()) != 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "적재 위치 예약을 해제하지 못했습니다.");
        }

        if (transportTaskMapper.updateStatus(
                taskCode, target, startedAt, pickedUpAt, completedAt, failedAt) != 1) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION);
        }
        return getDetail(taskCode);
    }

    @Transactional(readOnly = true)
    public TransportTask getTask(String taskCode) {
        return getTaskOrThrow(taskCode);
    }

    @Transactional
    public void driveToCompleted(String taskCode) {
        List<TaskStatus> path = List.of(
                TaskStatus.ASSIGNED, TaskStatus.MOVING_TO_PICKUP, TaskStatus.PICKING_UP,
                TaskStatus.TRANSPORTING, TaskStatus.PLACING, TaskStatus.COMPLETED);
        TaskStatus current = getTaskOrThrow(taskCode).getStatus();
        int index = path.indexOf(current);
        if (index < 0) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION);
        }
        for (int i = index + 1; i < path.size(); i++) {
            changeStatus(taskCode, path.get(i).name());
        }
    }

    @Transactional
    public void driveToFailed(String taskCode) {
        changeStatus(taskCode, TaskStatus.FAILED.name());
    }

    private void requireIdleVehicle(String vehicleId) {
        VehicleCurrentStatus current = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        if (current == null || current.getStatus() != VehicleStatus.IDLE) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "IDLE 상태 차량만 배정할 수 있습니다: " + vehicleId);
        }
    }

    private List<PlacementCandidate> loadEmptyCandidates() {
        return storageSlotMapper.findAllEmptySlotsForPlacement().stream()
                .map(this::toCandidate)
                .toList();
    }

    private PlacementCandidate toCandidate(StorageSlotPlacementRow row) {
        return new PlacementCandidate(
                row.getSlotCode(), row.getUsableHeight(), row.getForkHeight(),
                row.getDestinationX(), row.getDestinationY(), row.getDestinationHeading(),
                null, row.getStatus());
    }

    private TransportTask getTaskOrThrow(String taskCode) {
        return transportTaskMapper.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSPORT_TASK_NOT_FOUND));
    }

    private TaskStatus parseStatus(String raw) {
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 작업 상태입니다: " + raw);
        }
    }
}
