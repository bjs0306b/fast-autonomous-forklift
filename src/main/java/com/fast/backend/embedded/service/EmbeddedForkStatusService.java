package com.fast.backend.embedded.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.domain.EmbeddedForkState;
import com.fast.backend.embedded.domain.VehicleForkCurrentStatus;
import com.fast.backend.embedded.dto.EmbeddedForkStatusMessage;
import com.fast.backend.embedded.dto.EmbeddedForkStatusResponse;
import com.fast.backend.embedded.mapper.VehicleForkCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.EmbeddedForkStatusEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * {@code forklift/{id}/fork-status} 메시지를 검증·저장·브로드캐스트한다(prompt29.md 8장·16장
 * {@code EmbeddedForkStatusService}). 포크 높이·{@code limitTop}은 절대 사용하지 않는다
 * (작업 원칙 12·13번, {@link EmbeddedForkStatusMessage} Javadoc 참고).
 */
@Service
public class EmbeddedForkStatusService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedForkStatusService.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleForkCurrentStatusMapper forkStatusMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public EmbeddedForkStatusService(VehicleMapper vehicleMapper, VehicleForkCurrentStatusMapper forkStatusMapper,
            VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.forkStatusMapper = forkStatusMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void handleForkStatus(EmbeddedForkStatusMessage message) {
        log.debug("Embedded fork status received: forkliftId={}, forkState={}",
                message.forkliftId(), message.forkState());
        try {
            if (!isValid(message)) {
                return;
            }
            if (!vehicleMapper.existsByVehicleId(message.forkliftId())) {
                log.warn("Embedded fork status broadcast skipped, vehicle not registered: forkliftId={}",
                        message.forkliftId());
                return;
            }
            EmbeddedForkState forkState = EmbeddedForkState.fromRaw(message.forkState()).orElse(null);
            if (forkState == null) {
                log.warn("Embedded fork status skipped, unknown forkState: forkliftId={}, forkState={}",
                        message.forkliftId(), message.forkState());
                return;
            }

            // limitBottom=true인데 forkState != BOTTOM인 조합은 실제 센서 순서가 미확정이라 메시지를
            // 거부하지 않고 경고만 남긴다(21장 검증 규칙).
            if (Boolean.TRUE.equals(message.limitBottom()) && forkState != EmbeddedForkState.BOTTOM) {
                log.warn("Embedded fork status: limitBottom=true but forkState != BOTTOM "
                                + "(sensor ordering not confirmed, message accepted as-is): forkliftId={}, forkState={}",
                        message.forkliftId(), forkState);
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            VehicleForkCurrentStatus entity = new VehicleForkCurrentStatus();
            entity.setForkliftId(message.forkliftId());
            entity.setForkState(forkState);
            entity.setLimitBottom(message.limitBottom());
            entity.setErrorCode(message.errorCode());
            entity.setMessageAt(message.timestamp());
            entity.setReceivedAt(receivedAt);
            entity.setUpdatedAt(receivedAt);
            forkStatusMapper.upsert(entity);

            EmbeddedForkStatusEventData data = new EmbeddedForkStatusEventData(
                    message.forkliftId(), message.forkState(), message.limitBottom(), message.errorCode(),
                    message.timestamp(), receivedAt);
            broadcaster.broadcastForkStatus(message.forkliftId(), data, message.timestamp());
        } catch (RuntimeException e) {
            log.error("Embedded fork status processing failed unexpectedly: forkliftId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public EmbeddedForkStatusResponse getCurrentForkStatus(String forkliftId) {
        vehicleMapper.findByVehicleId(forkliftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + forkliftId));
        VehicleForkCurrentStatus status = forkStatusMapper.findByForkliftId(forkliftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMBEDDED_FORK_STATUS_NOT_FOUND,
                        "포크 상태 정보가 없습니다: " + forkliftId));
        return new EmbeddedForkStatusResponse(
                status.getForkliftId(),
                status.getForkState() != null ? status.getForkState().name() : null,
                status.getLimitBottom(),
                status.getErrorCode(),
                status.getMessageAt(),
                status.getReceivedAt());
    }

    private boolean isValid(EmbeddedForkStatusMessage message) {
        if (message.forkliftId() == null || message.forkliftId().isBlank()) {
            log.warn("Embedded fork status skipped: forkliftId is null or blank");
            return false;
        }
        if (message.forkState() == null || message.forkState().isBlank()) {
            log.warn("Embedded fork status skipped: forkState is null or blank, forkliftId={}", message.forkliftId());
            return false;
        }
        if (message.limitBottom() == null) {
            log.warn("Embedded fork status skipped: limitBottom is null, forkliftId={}", message.forkliftId());
            return false;
        }
        if (message.timestamp() == null) {
            log.warn("Embedded fork status skipped: timestamp is null, forkliftId={}", message.forkliftId());
            return false;
        }
        return true;
    }
}
