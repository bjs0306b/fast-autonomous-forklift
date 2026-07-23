package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 차량 최신 상태 갱신의 유일한 진입점(prompt16.md 11장). REST 테스트 API와 MQTT 상태 토픽
 * ({@code forklift/{vehicleId}/status}, {@code ForkliftStatusService}, prompt20.md 11장)이
 * 모두 이 메서드 하나만 호출한다 — 호출자가 REST인지 MQTT인지는 이 클래스가 알지 못한다.
 *
 * <p>처리 순서(11장 요구 순서를 그대로 따름): 차량 존재 확인 → 상태값 정규화 → 선택 필드 검증 →
 * 기존 최신 상태와 비교(오래된 메시지 방지) → upsert → 수신 시각 갱신 → 상태 이력 insert(prompt22.md) →
 * WebSocket 브로드캐스트. 오래된 메시지로 판정되면 upsert도 이력 insert도 실행하지 않는다.
 *
 * <p><b>미등록 차량 처리 정책</b>: 이 메서드는 차량이 없으면 {@link ErrorCode#VEHICLE_NOT_FOUND}를 던진다.
 * REST 테스트 API(`VehicleStatusTestController`) 입장에서는 이것이 곧 "404 Not Found"라는 올바른 REST
 * 응답이 된다. MQTT 흐름({@code ForkliftStatusService})에서는 이 예외(및
 * {@link ErrorCode#VEHICLE_BATTERY_OUT_OF_RANGE})를 잡아 경고 로그로 다운그레이드하고 메시지를 조용히
 * 무시한다(prompt16.md 3장, prompt20.md 11장) — 이 다운그레이드 책임은 이 Service가 아니라 호출자 쪽에
 * 있다. Service 스스로 "누가 불렀는지"에 따라 동작을 바꾸지 않는 것이 이 설계의 핵심이다.
 */
@Service
public class VehicleStatusService {

    private static final Logger log = LoggerFactory.getLogger(VehicleStatusService.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    private final VehicleStatusHistoryMapper vehicleStatusHistoryMapper;
    private final VehicleWebSocketBroadcaster vehicleWebSocketBroadcaster;

    public VehicleStatusService(
            VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper vehicleCurrentStatusMapper,
            VehicleStatusHistoryMapper vehicleStatusHistoryMapper,
            VehicleWebSocketBroadcaster vehicleWebSocketBroadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
        this.vehicleStatusHistoryMapper = vehicleStatusHistoryMapper;
        this.vehicleWebSocketBroadcaster = vehicleWebSocketBroadcaster;
    }

    @Transactional
    public VehicleStatusResponse updateCurrentStatus(String vehicleId, VehicleStatusUpdateCommand command) {
        // 1. 차량 존재 확인 (자동 등록하지 않음 — prompt16.md 3장·11장 기본값)
        vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));

        // 2. 상태값 정규화 (알 수 없는 문자열은 UNKNOWN)
        VehicleStatus normalizedStatus = VehicleStatus.fromRaw(command.status());

        // 3. 선택 필드 검증 (battery는 존재할 때만 0~100 범위 검증 — REST 계층의 @Min/@Max와 별개로,
        //    이 Service가 어떤 호출자에게서 오든 동일하게 방어한다)
        validateBatteryRange(command.battery());

        LocalDateTime receivedAt = LocalDateTime.now();
        LocalDateTime effectiveMessageAt = command.messageAt() != null ? command.messageAt() : receivedAt;

        // 4. 기존 최신 상태와 비교 — 오래된 메시지가 최신 상태를 덮어쓰지 않도록 함
        VehicleCurrentStatus existing = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        if (isStale(existing, effectiveMessageAt)) {
            log.warn("Stale vehicle status message ignored: vehicleId={}, incomingMessageAt={}, "
                            + "existingMessageAt={}",
                    vehicleId, effectiveMessageAt, existing.getMessageAt());
            return toStatusResponse(existing);
        }

        // 5. vehicle_current_status upsert + 6. 수신 시각 갱신
        VehicleCurrentStatus newStatus = new VehicleCurrentStatus();
        newStatus.setVehicleId(vehicleId);
        newStatus.setStatus(normalizedStatus);
        newStatus.setBattery(command.battery());
        newStatus.setPositionX(command.positionX());
        newStatus.setPositionY(command.positionY());
        newStatus.setHeading(command.heading());
        newStatus.setSpeed(command.speed());
        newStatus.setMessageAt(effectiveMessageAt);
        newStatus.setReceivedAt(receivedAt);
        newStatus.setUpdatedAt(receivedAt);
        vehicleCurrentStatusMapper.upsert(newStatus);

        // 6. vehicle_status_history insert — upsert와 같은 트랜잭션(@Transactional)에서 실행되어,
        //    이력 insert가 실패하면 방금 반영한 current status upsert도 함께 롤백된다(prompt22.md 4장,
        //    "부분 성공이 발생하지 않도록 한다").
        vehicleStatusHistoryMapper.insert(toHistory(newStatus, receivedAt));

        log.info("Vehicle current status updated: vehicleId={}, status={}, battery={}, receivedAt={}",
                vehicleId, normalizedStatus, command.battery(), receivedAt);

        // 7. WebSocket 브로드캐스트는 실제 트랜잭션 커밋 뒤 실행한다. 커밋이 실패하거나 롤백되면
        //    아직 저장되지 않은 상태를 관제에 먼저 전송하지 않는다. Broadcaster는 자체적으로 전송
        //    예외를 흡수하므로 커밋된 DB 결과에는 영향을 주지 않는다.
        VehicleStatusResponse statusResponse = toStatusResponse(newStatus);
        broadcastStatusAfterCommit(vehicleId, statusResponse, effectiveMessageAt);

        return statusResponse;
    }

    private void broadcastStatusAfterCommit(
            String vehicleId, VehicleStatusResponse statusResponse, LocalDateTime occurredAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            vehicleWebSocketBroadcaster.broadcastStatus(vehicleId, statusResponse, occurredAt);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                vehicleWebSocketBroadcaster.broadcastStatus(vehicleId, statusResponse, occurredAt);
            }
        });
    }

    private void validateBatteryRange(Integer battery) {
        if (battery != null && (battery < 0 || battery > 100)) {
            throw new BusinessException(ErrorCode.VEHICLE_BATTERY_OUT_OF_RANGE,
                    "battery는 0~100 범위여야 합니다: " + battery);
        }
    }

    /**
     * 기존 상태가 없으면(최초 수신) 당연히 stale이 아니다. 기존 상태가 있고, 그 messageAt이 새로
     * 들어온 메시지의 messageAt보다 같거나 늦으면(더 최신이면) 새 메시지를 stale로 간주해 무시한다.
     */
    private boolean isStale(VehicleCurrentStatus existing, LocalDateTime incomingMessageAt) {
        if (existing == null || existing.getMessageAt() == null) {
            return false;
        }
        return !incomingMessageAt.isAfter(existing.getMessageAt());
    }

    private VehicleStatusHistory toHistory(VehicleCurrentStatus status, LocalDateTime createdAt) {
        VehicleStatusHistory history = new VehicleStatusHistory();
        history.setVehicleId(status.getVehicleId());
        history.setStatus(status.getStatus());
        history.setBattery(status.getBattery());
        history.setPositionX(status.getPositionX());
        history.setPositionY(status.getPositionY());
        history.setHeading(status.getHeading());
        history.setSpeed(status.getSpeed());
        history.setMessageAt(status.getMessageAt());
        history.setReceivedAt(status.getReceivedAt());
        history.setCreatedAt(createdAt);
        return history;
    }

    private VehicleStatusResponse toStatusResponse(VehicleCurrentStatus status) {
        return new VehicleStatusResponse(
                status.getStatus(),
                status.getBattery(),
                status.getPositionX(),
                status.getPositionY(),
                status.getHeading(),
                status.getSpeed(),
                status.getMessageAt(),
                status.getReceivedAt());
    }
}
