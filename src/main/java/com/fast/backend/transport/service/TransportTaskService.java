package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.StorageSlotMapper;
import com.fast.backend.storage.placement.PlacementCandidate;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.storage.placement.PlacementService;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskListResponse;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 운반 작업 생성·배정·상태 전이 — FR-202 최종 스키마(prompt85)로 재작성.
 *
 * <p>옛 구현과 달라진 점
 * <ul>
 *   <li>파렛트가 사라졌다. 픽업 좌표는 <b>요청이 직접</b> 준다({@link TransportTaskCreateRequest}),
 *       중복 작업 검사도 파렛트 기준 → <b>화물 기준</b>이다.</li>
 *   <li>배치 판단 입력이 화물 치수 → <b>측정 결과의 화물 높이</b>({@code station_measurement.cargo_height})다.
 *       평면 적합성은 판단하지 않는다 — 그 데이터가 스키마에 없다
 *       ({@link PlacementService} Javadoc 의 경고 참고).</li>
 *   <li>슬롯 참조가 id → {@code slot_code}. 예약/점유 전이도 코드 기준이다.</li>
 *   <li>{@code updated_at} 컬럼이 없어 상태 전이 시각은 각 전용 컬럼에만 남는다.</li>
 * </ul>
 */
@Service
public class TransportTaskService {

    private static final Logger log = LoggerFactory.getLogger(TransportTaskService.class);

    private static final DateTimeFormatter TASK_CODE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CargoMapper cargoMapper;
    private final StationMeasurementMapper measurementMapper;
    private final StorageSlotMapper storageSlotMapper;
    private final TransportTaskMapper transportTaskMapper;
    private final PlacementService placementService;
    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    public TransportTaskService(
            CargoMapper cargoMapper, StationMeasurementMapper measurementMapper,
            StorageSlotMapper storageSlotMapper, TransportTaskMapper transportTaskMapper,
            PlacementService placementService, VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper vehicleCurrentStatusMapper) {
        this.cargoMapper = cargoMapper;
        this.measurementMapper = measurementMapper;
        this.storageSlotMapper = storageSlotMapper;
        this.transportTaskMapper = transportTaskMapper;
        this.placementService = placementService;
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
    }

    /** 추천 + 슬롯 조건부 예약 + Task insert 를 하나의 트랜잭션으로 처리한다. */
    @Transactional
    public TransportTaskResponse createTask(TransportTaskCreateRequest request) {
        if (!cargoMapper.existsByCargoId(request.cargoId())) {
            throw new BusinessException(ErrorCode.CARGO_NOT_FOUND,
                    "등록되지 않은 화물입니다: " + request.cargoId());
        }
        StationMeasurement measurement = measurementMapper.findByMeasurementId(request.measurementId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "존재하지 않는 측정 결과입니다: " + request.measurementId()));
        if (transportTaskMapper.existsOpenTaskByCargoId(request.cargoId())) {
            throw new BusinessException(ErrorCode.CARGO_ALREADY_ASSIGNED,
                    "이미 진행 중인 작업이 있는 화물입니다: " + request.cargoId());
        }

        PlacementRecommendation rec = placementService.recommend(
                measurement.getCargoHeight(), loadEmptyCandidates(), request.sourceX(), request.sourceY());

        LocalDateTime now = LocalDateTime.now();
        TransportTask task = new TransportTask();
        task.setTaskCode(generateTaskCode());
        task.setCargoId(request.cargoId());
        task.setMeasurementId(measurement.getMeasurementId());
        task.setVehicleId(null);
        task.setSourceX(request.sourceX());
        task.setSourceY(request.sourceY());
        task.setSourceHeading(request.sourceHeading());
        task.setDestinationSlotCode(rec.slotCode());
        task.setDestinationX(rec.destinationX());
        task.setDestinationY(rec.destinationY());
        task.setDestinationHeading(rec.destinationHeading());
        task.setForkHeight(rec.forkHeight());
        task.setStatus(TaskStatus.PENDING);
        task.setCreatedAt(now);
        transportTaskMapper.insert(task);

        // 조건부 예약: EMPTY 일 때만 성공. 실패하면 예외로 트랜잭션 전체(Task insert 포함)를 롤백한다.
        int reserved = storageSlotMapper.reserveIfEmpty(rec.slotCode(), task.getId());
        if (reserved != 1) {
            throw new BusinessException(ErrorCode.STORAGE_SLOT_ALREADY_RESERVED,
                    "추천 슬롯이 이미 예약되었습니다: slotCode=" + rec.slotCode());
        }

        log.info("Transport task created: taskCode={}, cargoId={}, measurementId={}, slotCode={}",
                task.getTaskCode(), request.cargoId(), measurement.getMeasurementId(), rec.slotCode());
        return TransportTaskResponse.of(task);
    }

    /** 수동 차량 배정. 사전 검증 후 조건부 UPDATE 로 원자적 배정. */
    @Transactional
    public TransportTaskResponse assign(String taskCode, String vehicleId) {
        TransportTask task = getTaskOrThrow(taskCode);
        if (task.getStatus() != TaskStatus.PENDING || task.getVehicleId() != null) {
            throw new BusinessException(ErrorCode.TASK_ALREADY_ASSIGNED,
                    "PENDING이 아니거나 이미 배정된 작업입니다: " + taskCode);
        }
        if (!vehicleMapper.existsByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: " + vehicleId);
        }
        requireAssignableVehicle(vehicleId);
        if (transportTaskMapper.existsActiveTaskByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_ALREADY_ASSIGNED,
                    "이미 활성 작업이 있는 차량입니다: " + vehicleId);
        }

        int updated = transportTaskMapper.updateAssignment(taskCode, vehicleId, LocalDateTime.now());
        if (updated != 1) {
            // 사전 검증과 UPDATE 사이에 다른 요청이 먼저 배정한 경우(경쟁) — 조건부 UPDATE 가 최종 방어선.
            throw new BusinessException(ErrorCode.TASK_ALREADY_ASSIGNED,
                    "동시 배정 경쟁으로 배정에 실패했습니다: " + taskCode);
        }
        log.info("Transport task assigned: taskCode={}, vehicleId={}", taskCode, vehicleId);
        return getDetail(taskCode);
    }

    @Transactional(readOnly = true)
    public TransportTaskResponse getDetail(String taskCode) {
        return TransportTaskResponse.of(getTaskOrThrow(taskCode));
    }

    @Transactional(readOnly = true)
    public TransportTaskListResponse list(int page, int size, TaskStatus status, String vehicleId) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int offset = safePage * safeSize;
        List<TransportTask> tasks = transportTaskMapper.findAll(status, vehicleId, safeSize, offset);
        long total = transportTaskMapper.countAll(status, vehicleId);
        List<TransportTaskResponse> items = new ArrayList<>();
        for (TransportTask task : tasks) {
            items.add(TransportTaskResponse.of(task));
        }
        return new TransportTaskListResponse(items, safePage, safeSize, total);
    }

    /** 상태 변경 + 슬롯 상태 처리. 전체가 하나의 트랜잭션이다. */
    @Transactional
    public TransportTaskResponse changeStatus(String taskCode, String rawStatus) {
        TransportTask task = getTaskOrThrow(taskCode);
        TaskStatus target = parseStatus(rawStatus);
        TaskStatus.validateTransition(task.getStatus(), target);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startedAt = null;
        LocalDateTime pickedUpAt = null;
        LocalDateTime completedAt = null;
        LocalDateTime failedAt = null;

        switch (target) {
            case ASSIGNED -> {
                if (task.getVehicleId() == null) {
                    throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                            "ASSIGNED 상태에는 vehicleId가 필요합니다: " + taskCode);
                }
            }
            case MOVING_TO_PICKUP -> startedAt = now;
            case PICKING_UP -> pickedUpAt = now;
            case COMPLETED -> {
                completedAt = now;
                // RESERVED → OCCUPIED. CHECK 제약상 예약과 적재는 동시에 설정될 수 없어 Mapper 가 함께 정리한다.
                storageSlotMapper.markOccupied(task.getDestinationSlotCode(), task.getCargoId());
            }
            case FAILED -> {
                failedAt = now;
                storageSlotMapper.releaseReservation(task.getDestinationSlotCode());
            }
            case CANCELLED -> storageSlotMapper.releaseReservation(task.getDestinationSlotCode());
            default -> { /* PENDING/TRANSPORTING/PLACING: 슬롯 상태 변화 없음 */ }
        }

        transportTaskMapper.updateStatus(taskCode, target, startedAt, pickedUpAt, completedAt, failedAt);
        log.info("Transport task status changed: taskCode={}, {} -> {}", taskCode, task.getStatus(), target);
        return getDetail(taskCode);
    }

    /**
     * MQTT 운반 결과가 성공일 때 작업을 종료 상태로 보낸다(prompt48.md 흐름 유지).
     *
     * <p>중간 상태를 일일이 거치지 않고 곧바로 COMPLETED 로 전이한다 — 차량이 이미 끝냈다고 보고한
     * 시점이라 중간 단계를 되짚는 것이 의미가 없다. 슬롯은 RESERVED → OCCUPIED 로 확정한다.
     */
    @Transactional
    public void driveToCompleted(String taskCode) {
        TransportTask task = getTaskOrThrow(taskCode);
        LocalDateTime now = LocalDateTime.now();
        storageSlotMapper.markOccupied(task.getDestinationSlotCode(), task.getCargoId());
        transportTaskMapper.updateStatus(taskCode, TaskStatus.COMPLETED, null, null, now, null);
        log.info("Transport task completed by command result: taskCode={}", taskCode);
    }

    /** MQTT 운반 결과가 실패일 때. 예약한 슬롯을 반드시 되돌린다(잡아 둔 자리가 남으면 안 된다). */
    @Transactional
    public void driveToFailed(String taskCode) {
        TransportTask task = getTaskOrThrow(taskCode);
        LocalDateTime now = LocalDateTime.now();
        storageSlotMapper.releaseReservation(task.getDestinationSlotCode());
        transportTaskMapper.updateStatus(taskCode, TaskStatus.FAILED, null, null, null, now);
        log.info("Transport task failed by command result: taskCode={}", taskCode);
    }

    /** taskCode 로 Task 엔티티를 조회한다(디스패치·결과 처리에서 재사용). */
    @Transactional(readOnly = true)
    public TransportTask getTask(String taskCode) {
        return getTaskOrThrow(taskCode);
    }

    private List<PlacementCandidate> loadEmptyCandidates() {
        List<PlacementCandidate> candidates = new ArrayList<>();
        for (StorageSlot slot : storageSlotMapper.findAllEmptySlots()) {
            candidates.add(new PlacementCandidate(
                    slot.getSlotCode(),
                    slot.getUsableHeight() == null ? 0.0 : slot.getUsableHeight(),
                    slot.getForkHeight(),
                    slot.getDestinationX(),
                    slot.getDestinationY(),
                    slot.getDestinationHeading(),
                    slot.getStatus()));
        }
        return candidates;
    }

    private void requireAssignableVehicle(String vehicleId) {
        VehicleCurrentStatus status = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        if (status == null || status.getStatus() == null) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "상태를 알 수 없는 차량에는 배정할 수 없습니다: " + vehicleId);
        }
        if (status.getStatus() != VehicleStatus.IDLE) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "IDLE 상태가 아닌 차량입니다: vehicleId=" + vehicleId + ", status=" + status.getStatus());
        }
    }

    private TransportTask getTaskOrThrow(String taskCode) {
        return transportTaskMapper.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSPORT_TASK_NOT_FOUND,
                        "존재하지 않는 작업입니다: " + taskCode));
    }

    private TaskStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION, "status는 필수입니다.");
        }
        try {
            return TaskStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION, "알 수 없는 status입니다: " + rawStatus);
        }
    }

    private String generateTaskCode() {
        return "TASK-" + LocalDateTime.now().format(TASK_CODE_TIME)
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
