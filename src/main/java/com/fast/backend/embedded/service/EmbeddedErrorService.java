package com.fast.backend.embedded.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.domain.EmbeddedErrorHistory;
import com.fast.backend.embedded.domain.EmbeddedErrorSeverity;
import com.fast.backend.embedded.domain.EmbeddedErrorSource;
import com.fast.backend.embedded.dto.EmbeddedErrorMessage;
import com.fast.backend.embedded.dto.EmbeddedErrorResponse;
import com.fast.backend.embedded.mapper.EmbeddedErrorHistoryMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.EmbeddedErrorEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code forklift/{id}/error} 메시지를 검증·저장·브로드캐스트한다(prompt29.md 9장·16장
 * {@code EmbeddedErrorService}).
 *
 * <p>{@code CRITICAL}을 받았다는 이유만으로 비상정지를 자동 재발행하지 않는다(16장 명시 조건) — 이
 * 클래스는 저장·전달만 하고, 자동 비상정지 로직은 별도 합의가 없어 의도적으로 구현하지 않았다.
 */
@Service
public class EmbeddedErrorService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedErrorService.class);

    private final VehicleMapper vehicleMapper;
    private final EmbeddedErrorHistoryMapper errorHistoryMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public EmbeddedErrorService(VehicleMapper vehicleMapper, EmbeddedErrorHistoryMapper errorHistoryMapper,
            VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.errorHistoryMapper = errorHistoryMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void handleError(EmbeddedErrorMessage message) {
        log.debug("Embedded error received: forkliftId={}, errorCode={}, severity={}",
                message.forkliftId(), message.errorCode(), message.severity());
        try {
            if (!isValid(message)) {
                return;
            }
            if (!vehicleMapper.existsByVehicleId(message.forkliftId())) {
                log.warn("Embedded error broadcast skipped, vehicle not registered: forkliftId={}", message.forkliftId());
                return;
            }
            EmbeddedErrorSource source = EmbeddedErrorSource.fromRaw(message.errorSource()).orElse(null);
            if (source == null) {
                log.warn("Embedded error skipped, unknown errorSource: forkliftId={}, errorSource={}",
                        message.forkliftId(), message.errorSource());
                return;
            }
            EmbeddedErrorSeverity severity = EmbeddedErrorSeverity.fromRaw(message.severity()).orElse(null);
            if (severity == null) {
                log.warn("Embedded error skipped, unknown severity: forkliftId={}, severity={}",
                        message.forkliftId(), message.severity());
                return;
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            EmbeddedErrorHistory entity = new EmbeddedErrorHistory();
            entity.setForkliftId(message.forkliftId());
            entity.setErrorCode(message.errorCode());
            entity.setErrorSource(source);
            entity.setSeverity(severity);
            entity.setMessage(message.message());
            entity.setOccurredAt(message.timestamp());
            entity.setReceivedAt(receivedAt);
            entity.setCreatedAt(receivedAt);
            errorHistoryMapper.insert(entity);

            EmbeddedErrorEventData data = new EmbeddedErrorEventData(
                    message.forkliftId(), message.errorCode(), message.errorSource(), message.severity(),
                    message.message(), message.timestamp(), receivedAt);
            broadcaster.broadcastEmbeddedError(message.forkliftId(), data, message.timestamp());
        } catch (RuntimeException e) {
            log.error("Embedded error processing failed unexpectedly: forkliftId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<EmbeddedErrorResponse> findRecentByForkliftId(String forkliftId, int limit) {
        if (limit < 1 || limit > 200) {
            throw new BusinessException(ErrorCode.EMBEDDED_COMMAND_LIMIT_INVALID,
                    "limit은 1~200 범위여야 합니다: " + limit);
        }
        vehicleMapper.findByVehicleId(forkliftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + forkliftId));
        return errorHistoryMapper.findRecentByForkliftId(forkliftId, limit).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private EmbeddedErrorResponse toResponse(EmbeddedErrorHistory history) {
        return new EmbeddedErrorResponse(
                history.getId(),
                history.getForkliftId(),
                history.getErrorCode(),
                history.getErrorSource() != null ? history.getErrorSource().name() : null,
                history.getSeverity() != null ? history.getSeverity().name() : null,
                history.getMessage(),
                history.getOccurredAt(),
                history.getReceivedAt());
    }

    private boolean isValid(EmbeddedErrorMessage message) {
        if (message.forkliftId() == null || message.forkliftId().isBlank()) {
            log.warn("Embedded error skipped: forkliftId is null or blank");
            return false;
        }
        if (message.errorCode() == null || message.errorCode().isBlank()) {
            log.warn("Embedded error skipped: errorCode is null or blank, forkliftId={}", message.forkliftId());
            return false;
        }
        if (message.errorSource() == null || message.errorSource().isBlank()) {
            log.warn("Embedded error skipped: errorSource is null or blank, forkliftId={}", message.forkliftId());
            return false;
        }
        if (message.severity() == null || message.severity().isBlank()) {
            log.warn("Embedded error skipped: severity is null or blank, forkliftId={}", message.forkliftId());
            return false;
        }
        if (message.timestamp() == null) {
            log.warn("Embedded error skipped: timestamp is null, forkliftId={}", message.forkliftId());
            return false;
        }
        return true;
    }
}
