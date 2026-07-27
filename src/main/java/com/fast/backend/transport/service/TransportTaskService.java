package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;
import com.fast.backend.storage.domain.StorageSlot;
import com.fast.backend.storage.domain.StorageSlotStatus;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.PalletMapper;
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
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 운반 작업 생성·배정·조회·상태변경(prompt47.md 8·9·10·11장). 추천은 기존 {@link PlacementService},
 * 상태 전이는 기존 {@link TaskStatus#validateTransition}, 슬롯 전이는 {@link StorageSlotStatus}를 재사용한다.
 *
 * <p>동시성·중복 방지의 핵심은 <b>조건부 UPDATE</b>다: 슬롯은
 * {@link StorageSlotMapper#reserveIfEmpty}({@code WHERE status='EMPTY'}), 배정은
 * {@link TransportTaskMapper#updateAssignment}({@code WHERE status='PENDING' AND vehicle_id IS NULL}).
 * update count가 1일 때만 성공으로 판정한다(조회 후 save 방식 금지).
 */
@Service
public class TransportTaskService {

    private static final Logger log = LoggerFactory.getLogger(TransportTaskService.class);

    private final CargoMapper cargoMapper;
    private final PalletMapper palletMapper;
    private final StorageSlotMapper storageSlotMapper;
    private final TransportTaskMapper transportTaskMapper;
    private final PlacementService placementService;
    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    public TransportTaskService(
            CargoMapper cargoMapper, PalletMapper palletMapper, StorageSlotMapper storageSlotMapper,
            TransportTaskMapper transportTaskMapper, PlacementService placementService,
            VehicleMapper vehicleMapper, VehicleCurrentStatusMapper vehicleCurrentStatusMapper) {
        this.cargoMapper = cargoMapper;
        this.palletMapper = palletMapper;
        this.storageSlotMapper = storageSlotMapper;
        this.transportTaskMapper = transportTaskMapper;
        this.placementService = placementService;
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
    }

    /** 추천 + 슬롯 조건부 예약 + Task insert를 하나의 트랜잭션으로 처리한다(prompt47.md 8장). */
    @Transactional
    public TransportTaskResponse createTask(TransportTaskCreateRequest request) {
        Cargo cargo = cargoMapper.findByCargoId(request.cargoId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CARGO_NOT_FOUND,
                        "등록되지 않은 화물입니다: " + request.cargoId()));
        Pallet pallet = palletMapper.findByPalletId(request.palletId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PALLET_NOT_FOUND,
                        "등록되지 않은 팔레트입니다: " + request.palletId()));
        if (!pallet.getCargoId().equals(cargo.getCargoId())) {
            throw new BusinessException(ErrorCode.PALLET_CARGO_MISMATCH,
                    "팔레트의 화물과 요청 화물이 다릅니다: pallet=" + pallet.getCargoId() + ", request=" + cargo.getCargoId());
        }
        if (transportTaskMapper.existsOpenTaskByPalletId(pallet.getPalletId())) {
            throw new BusinessException(ErrorCode.PALLET_ALREADY_ASSIGNED,
                    "이미 진행 중인 작업이 있는 팔레트입니다: " + pallet.getPalletId());
        }

        List<PlacementCandidate> candidates = loadEmptyCandidates();
        PlacementRecommendation rec =
                placementService.recommend(cargo, candidates, pallet.getPickupX(), pallet.getPickupY());

        LocalDateTime now = LocalDateTime.now();
        TransportTask task = new TransportTask();
        task.setTaskCode(generateTaskCode());
        task.setCargoId(cargo.getCargoId());
        task.setPalletId(pallet.getPalletId());
        task.setVehicleId(null);
        task.setSourceX(pallet.getPickupX());
        task.setSourceY(pallet.getPickupY());
        task.setSourceHeading(pallet.getPickupHeading());
        task.setDestinationSlotId(rec.slotId());
        task.setDestinationX(rec.destinationX());
        task.setDestinationY(rec.destinationY());
        task.setDestinationHeading(rec.destinationHeading());
        task.setForkHeight(rec.forkHeight());
        task.setCargoOrientation(rec.orientation());
        task.setStatus(TaskStatus.PENDING);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        transportTaskMapper.insert(task);

        // 조건부 예약: EMPTY일 때만 성공. 실패하면 예외로 트랜잭션 전체(Task insert 포함) 롤백.
        int reserved = storageSlotMapper.reserveIfEmpty(rec.slotId(), task.getTaskCode(), now);
        if (reserved != 1) {
            throw new BusinessException(ErrorCode.STORAGE_SLOT_ALREADY_RESERVED,
                    "추천 슬롯이 이미 예약되었습니다: slotId=" + rec.slotId());
        }

        log.info("Transport task created: taskCode={}, cargoId={}, slotId={}, orientation={}",
                task.getTaskCode(), cargo.getCargoId(), rec.slotId(), rec.orientation());

        StorageSlotPlacementRow slot = storageSlotMapper.findPlacementRowById(rec.slotId()).orElse(null);
        return TransportTaskResponse.of(task, cargo, slot);
    }

    /** 수동 차량 배정(prompt47.md 9장). 사전 검증 후 조건부 UPDATE로 원자적 배정. */
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
        if (transportTaskMapper.existsActiveTaskByPalletId(task.getPalletId())) {
            throw new BusinessException(ErrorCode.PALLET_ALREADY_ASSIGNED,
                    "이미 다른 활성 작업이 사용 중인 팔레트입니다: " + task.getPalletId());
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = transportTaskMapper.updateAssignment(taskCode, vehicleId, now, now);
        if (updated != 1) {
            // 사전 검증과 UPDATE 사이에 다른 요청이 먼저 배정한 경우(경쟁) — 조건부 UPDATE가 최종 방어선.
            throw new BusinessException(ErrorCode.TASK_ALREADY_ASSIGNED,
                    "동시 배정 경쟁으로 배정에 실패했습니다: " + taskCode);
        }
        palletMapper.updateStatus(task.getPalletId(), PalletStatus.ASSIGNED, now);
        log.info("Transport task assigned: taskCode={}, vehicleId={}", taskCode, vehicleId);
        return getDetail(taskCode);
    }

    @Transactional(readOnly = true)
    public TransportTaskResponse getDetail(String taskCode) {
        TransportTask task = getTaskOrThrow(taskCode);
        Cargo cargo = cargoMapper.findByCargoId(task.getCargoId()).orElse(null);
        StorageSlotPlacementRow slot = task.getDestinationSlotId() != null
                ? storageSlotMapper.findPlacementRowById(task.getDestinationSlotId()).orElse(null)
                : null;
        return TransportTaskResponse.of(task, cargo, slot);
    }

    @Transactional(readOnly = true)
    public TransportTaskListResponse list(
            int page, int size, TaskStatus status, String vehicleId, String palletId) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? 20 : Math.min(size, 200);
        int offset = safePage * safeSize;
        List<TransportTask> tasks = transportTaskMapper.findAll(status, vehicleId, palletId, safeSize, offset);
        long total = transportTaskMapper.countAll(status, vehicleId, palletId);
        List<TransportTaskResponse> items = new ArrayList<>();
        for (TransportTask task : tasks) {
            items.add(TransportTaskResponse.of(task, null, null));
        }
        return new TransportTaskListResponse(items, safePage, safeSize, total);
    }

    /** 상태 변경 + 연관(Pallet/StorageSlot) 상태 처리(prompt47.md 11장). 전체가 하나의 트랜잭션. */
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
            case PICKING_UP -> {
                pickedUpAt = now;
                palletMapper.updateStatus(task.getPalletId(), PalletStatus.PICKED_UP, now);
            }
            case TRANSPORTING -> palletMapper.updateStatus(task.getPalletId(), PalletStatus.TRANSPORTING, now);
            case PLACING -> { /* 별도 연관 변경 없음 */ }
            case COMPLETED -> {
                completedAt = now;
                transitionSlot(task.getDestinationSlotId(), StorageSlotStatus.OCCUPIED, task.getCargoId(), now);
                palletMapper.updateStatus(task.getPalletId(), PalletStatus.STORED, now);
            }
            case FAILED -> {
                failedAt = now;
                transitionSlot(task.getDestinationSlotId(), StorageSlotStatus.EMPTY, null, now);
                // 정책 미확정: 실패 시 팔레트는 보수적으로 FAILED로 둔다(prompt47.md 11장, 문서화 대상).
                palletMapper.updateStatus(task.getPalletId(), PalletStatus.FAILED, now);
            }
            case CANCELLED -> {
                transitionSlot(task.getDestinationSlotId(), StorageSlotStatus.EMPTY, null, now);
                palletMapper.updateStatus(task.getPalletId(), PalletStatus.WAITING, now);
            }
            default -> { /* PENDING 등: 진입 불가(validateTransition에서 차단됨) */ }
        }

        transportTaskMapper.updateStatus(taskCode, target, startedAt, pickedUpAt, completedAt, failedAt, now);
        log.info("Transport task status changed: taskCode={}, {} -> {}", taskCode, task.getStatus(), target);
        return getDetail(taskCode);
    }

    /** taskCode로 Task 엔티티를 조회한다(디스패치·결과 처리에서 재사용). */
    @Transactional(readOnly = true)
    public TransportTask getTask(String taskCode) {
        return getTaskOrThrow(taskCode);
    }

    /**
     * 최종 성공 결과 반영용: 현재 상태에서 COMPLETED까지 정상 흐름을 순차 전이한다(prompt48.md 11·13장).
     * 차량이 최종 결과만 보내는 경우(단계 미보고)에도 기존 {@link #changeStatus} 완료 처리(슬롯 OCCUPIED,
     * 화물 STORED)를 그대로 재사용하기 위해, 중간 상태를 백엔드가 추측 생성하지 않고 "정상 경로를 빠르게
     * 통과"시키는 방식이다. 각 단계는 {@link TaskStatus#validateTransition}으로 검증된다.
     */
    @Transactional
    public void driveToCompleted(String taskCode) {
        List<TaskStatus> happyPath = List.of(
                TaskStatus.ASSIGNED, TaskStatus.MOVING_TO_PICKUP, TaskStatus.PICKING_UP,
                TaskStatus.TRANSPORTING, TaskStatus.PLACING, TaskStatus.COMPLETED);
        TransportTask task = getTaskOrThrow(taskCode);
        int start = happyPath.indexOf(task.getStatus());
        if (start < 0) {
            throw new BusinessException(ErrorCode.INVALID_TASK_STATUS_TRANSITION,
                    "완료 처리할 수 없는 현재 상태입니다: " + task.getStatus());
        }
        for (int i = start + 1; i < happyPath.size(); i++) {
            changeStatus(taskCode, happyPath.get(i).name());
        }
    }

    /** 최종 실패 결과 반영용: 현재 상태에서 FAILED로 전이한다(기존 완료 처리 재사용). */
    @Transactional
    public void driveToFailed(String taskCode) {
        changeStatus(taskCode, TaskStatus.FAILED.name());
    }

    /** 슬롯 상태 전이를 방어적으로 검증한 뒤 조건부 UPDATE를 실행한다(prompt47.md 12장). */
    private void transitionSlot(Long slotId, StorageSlotStatus target, String storedCargoId, LocalDateTime now) {
        if (slotId == null) {
            return;
        }
        StorageSlot slot = storageSlotMapper.findById(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORAGE_SLOT_NOT_FOUND,
                        "존재하지 않는 슬롯입니다: " + slotId));
        if (!slot.getStatus().canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "허용되지 않는 슬롯 상태 전이입니다: " + slot.getStatus() + " → " + target);
        }
        int affected = switch (target) {
            case OCCUPIED -> storageSlotMapper.markOccupied(slotId, storedCargoId, now);
            case EMPTY -> storageSlotMapper.releaseReservation(slotId, now);
            default -> 0;
        };
        if (affected != 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "슬롯 상태 전이에 실패했습니다: slotId=" + slotId + ", target=" + target);
        }
    }

    /**
     * 배정 가능한 차량인지 검증한다. 이 프로젝트에는 별도 online 플래그 컬럼이 없어(prompt44 확인),
     * {@code vehicle_current_status.status}로 판단한다 — status가 IDLE이면 온라인·비오류로 간주하고,
     * OFFLINE/ERROR/ESTOP 등은 IDLE이 아니므로 자연히 제외된다. 상태 행 자체가 없으면 배정 불가.
     */
    private void requireAssignableVehicle(String vehicleId) {
        VehicleCurrentStatus current = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        if (current == null || current.getStatus() != VehicleStatus.IDLE) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_AVAILABLE,
                    "IDLE 상태의 차량만 배정할 수 있습니다: " + vehicleId
                            + ", status=" + (current == null ? "NONE" : current.getStatus()));
        }
    }

    private List<PlacementCandidate> loadEmptyCandidates() {
        List<PlacementCandidate> candidates = new ArrayList<>();
        for (StorageSlotPlacementRow row : storageSlotMapper.findAllEmptySlotsForPlacement()) {
            candidates.add(new PlacementCandidate(
                    row.getSlotId(), row.getSlotCode(), row.getRackCode(), row.getLevelNumber(),
                    row.getSlotWidth(), row.getSlotLength(), row.getSlotHeight(),
                    row.getDestinationX(), row.getDestinationY(), row.getDestinationHeading(),
                    row.getForkHeight(), row.getStatus()));
        }
        return candidates;
    }

    private TransportTask getTaskOrThrow(String taskCode) {
        return transportTaskMapper.findByTaskCode(taskCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSPORT_TASK_NOT_FOUND,
                        "존재하지 않는 운반 작업입니다: " + taskCode));
    }

    private TaskStatus parseStatus(String raw) {
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "알 수 없는 작업 상태입니다: " + raw);
        }
    }

    /**
     * 충돌 안전한 taskCode 생성(prompt47.md 8장 "단순 건수+1 금지"). UUID 기반이라 동시 생성에도 충돌하지
     * 않으며, task_code UNIQUE 제약이 최종 방어선이다.
     */
    private String generateTaskCode() {
        return "TASK-" + UUID.randomUUID();
    }
}
