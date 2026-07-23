package com.fast.backend.isaac.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.isaac.domain.IsaacForkliftStatus;
import com.fast.backend.isaac.dto.IsaacForkliftStatusMessage;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleStatusService;
import com.fast.backend.vehicle.websocket.IsaacVehicleStatusEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Isaac Sim 상태 메시지(LWT OFFLINE 포함)를 처리한다(prompt28.md 4장·5장).
 *
 * <p>DB 반영은 {@code forkliftId}가 곧 차량 도메인의 {@code vehicleId}라는 전제로
 * {@link VehicleStatusService#updateCurrentStatus}에 위임한다 — 기존 {@code ForkliftStatusService}와
 * 동일한 패턴이며, {@code VehicleStatusUpdateCommand} Javadoc이 애초에 "IsaacSimVehicleStatusAdapter가
 * 이 메서드를 호출하게 될 것"이라고 예고해 둔 지점이다(prompt16.md 18장). 즉 상태 현재값 갱신·이력
 * insert·기존 형태 WebSocket 브로드캐스트(prompt22.md)를 전부 그대로 재사용한다(요구사항 2장 4번
 * "수신 데이터를 기존 차량 관제 기능과 연결").
 *
 * <p>다만 {@code VehicleStatusUpdateCommand}에는 forkHeight/hasCargo/cargoId/footprint를 담을 자리가
 * 없고, {@code vehicle_current_status}도 공통 {@link com.fast.backend.vehicle.domain.VehicleStatus}
 * (5종)만 저장할 수 있어 Isaac 원본 상태(7종)의 세부 정보가 손실된다({@link IsaacForkliftStatus}
 * Javadoc 참고). 그래서 이 클래스는 DB 갱신과 별개로 {@link IsaacVehicleStatusEventData}(원본 상태 +
 * forkHeight/hasCargo/cargoId/footprint 전부 포함)를 추가로 브로드캐스트해 정보를 보존한다.
 */
@Service
public class IsaacForkliftStatusService {

    private static final Logger log = LoggerFactory.getLogger(IsaacForkliftStatusService.class);

    private final VehicleStatusService vehicleStatusService;
    private final VehicleWebSocketBroadcaster broadcaster;

    public IsaacForkliftStatusService(VehicleStatusService vehicleStatusService, VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleStatusService = vehicleStatusService;
        this.broadcaster = broadcaster;
    }

    public void handleStatus(IsaacForkliftStatusMessage message) {
        log.debug("Isaac forklift status message received: forkliftId={}, status={}",
                message.forkliftId(), message.status());

        try {
            if (!isValid(message)) {
                return;
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            // LWT의 timestamp는 null일 수 있다 — 이 경우 백엔드 수신 시각을 대신 기록한다(5장 4번).
            LocalDateTime effectiveTimestamp = message.timestamp() != null ? message.timestamp() : receivedAt;

            String commonStatusRaw = IsaacForkliftStatus.fromRaw(message.status())
                    .map(IsaacForkliftStatus::toCommonVehicleStatusRaw)
                    .orElse(message.status());
            VehicleStatusUpdateCommand command = new VehicleStatusUpdateCommand(
                    commonStatusRaw, message.battery(), null, null, null, null, effectiveTimestamp);

            try {
                vehicleStatusService.updateCurrentStatus(message.forkliftId(), command);
            } catch (BusinessException e) {
                // 미등록 차량 등 — 기존 ForkliftStatusService와 동일하게 경고 로그만 남기고 흡수한다.
                log.warn("Isaac forklift status update skipped: forkliftId={}, errorCode={}, message={}",
                        message.forkliftId(), e.getErrorCode(), e.getMessage());
                return;
            }

            IsaacVehicleStatusEventData data = toEventData(message, receivedAt);
            broadcaster.broadcastIsaacStatus(message.forkliftId(), data, effectiveTimestamp);
        } catch (RuntimeException e) {
            log.error("Isaac status processing failed unexpectedly: forkliftId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }

    /**
     * LWT OFFLINE 메시지({@code status == "OFFLINE"})는 battery/forkHeight/hasCargo/cargoId/footprint/
     * timestamp가 전부 없어도 거부하지 않는다(5장 1~3번). 그 외 일반 상태 메시지는 필수 필드를 모두
     * 검증한다(4장 검증 규칙).
     */
    private boolean isValid(IsaacForkliftStatusMessage message) {
        String forkliftId = message.forkliftId();
        if (forkliftId == null || forkliftId.isBlank()) {
            log.warn("Isaac status message skipped: forkliftId is null or blank");
            return false;
        }
        if (message.status() == null || message.status().isBlank()) {
            log.warn("Isaac status message skipped: status is null or blank, forkliftId={}", forkliftId);
            return false;
        }
        if (isLwtOffline(message)) {
            return true;
        }

        Integer battery = message.battery();
        if (battery == null || battery < 0 || battery > 100) {
            log.warn("Isaac status message skipped: battery out of range or missing, forkliftId={}", forkliftId);
            return false;
        }
        Double forkHeight = message.forkHeight();
        if (forkHeight == null || !isFinite(forkHeight) || forkHeight < 0) {
            log.warn("Isaac status message skipped: forkHeight invalid, forkliftId={}", forkliftId);
            return false;
        }
        if (message.hasCargo() == null) {
            log.warn("Isaac status message skipped: hasCargo is missing, forkliftId={}", forkliftId);
            return false;
        }
        // cargoId 조건부 필수 여부는 합의 문서에 없어 임의로 강제하지 않는다(4장 검증 규칙).
        IsaacForkliftStatusMessage.Footprint footprint = message.footprint();
        if (footprint == null) {
            log.warn("Isaac status message skipped: footprint is missing, forkliftId={}", forkliftId);
            return false;
        }
        Double length = footprint.length();
        Double width = footprint.width();
        if (length == null || !isFinite(length) || length <= 0) {
            log.warn("Isaac status message skipped: footprint.length invalid, forkliftId={}", forkliftId);
            return false;
        }
        if (width == null || !isFinite(width) || width <= 0) {
            log.warn("Isaac status message skipped: footprint.width invalid, forkliftId={}", forkliftId);
            return false;
        }
        return true;
    }

    private boolean isLwtOffline(IsaacForkliftStatusMessage message) {
        return "OFFLINE".equalsIgnoreCase(message.status());
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private IsaacVehicleStatusEventData toEventData(IsaacForkliftStatusMessage message, LocalDateTime receivedAt) {
        IsaacVehicleStatusEventData.Footprint footprint = message.footprint() != null
                ? new IsaacVehicleStatusEventData.Footprint(message.footprint().length(), message.footprint().width())
                : null;
        return new IsaacVehicleStatusEventData(
                message.forkliftId(),
                message.status(),
                message.battery(),
                message.forkHeight(),
                message.hasCargo(),
                message.cargoId(),
                footprint,
                message.timestamp(),
                receivedAt);
    }
}
