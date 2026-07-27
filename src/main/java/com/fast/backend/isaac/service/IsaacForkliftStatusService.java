package com.fast.backend.isaac.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.isaac.domain.IsaacForkliftStatus;
import com.fast.backend.isaac.dto.IsaacForkliftStatusMessage;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleStatusService;
import com.fast.backend.vehicle.websocket.IsaacVehicleStatusEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Isaac Sim 상태 메시지(LWT OFFLINE 포함)를 처리한다(prompt28.md 4장·5장).
 *
 * <p>DB 반영은 {@code forkliftId}가 곧 차량 도메인의 {@code vehicleId}라는 전제로
 * {@link VehicleStatusService#updateCurrentStatus}에 위임한다 — 기존 {@code ForkliftStatusService}와
 * 동일한 패턴이며, 상태 현재값 갱신·이력 insert·WebSocket 브로드캐스트를 전부 재사용한다.
 *
 * <p><b>정보 손실이 해소됐다(prompt32.md 1장 3번·4번 확정)</b>
 * <ul>
 *   <li>상태 어휘: {@link IsaacForkliftStatus} 7종이 확장된 공통 {@code VehicleStatus} 10종에 1:1로
 *       대응한다 — 더 이상 {@code LIFTING}/{@code LOADING}이 {@code ACTIVE}로, {@code ESTOP}이
 *       {@code ERROR}로 뭉뚱그려지지 않는다.</li>
 *   <li>확장 필드: {@code forkHeight}/{@code hasCargo}/{@code cargoId}/{@code footprint}를
 *       {@link VehicleStatusUpdateCommand.IsaacExtras}에 담아 넘겨 <b>DB에도 저장</b>한다. 이 값이
 *       담긴 커맨드는 Isaac 경로에서만 만들어지므로, ROS2 상태 메시지는 이 컬럼들을 건드리지 않는다
 *       ({@code VehicleStatusService}의 병합 정책).</li>
 * </ul>
 * WebSocket 이벤트({@link IsaacVehicleStatusEventData})는 Isaac <b>원본</b> 상태 문자열과 확장 필드를
 * 그대로 실어 계속 보낸다 — DB에는 공통 상태로 정규화된 값이 저장되므로, 원본 어휘를 그대로 보고 싶은
 * 프론트를 위한 통로로 유지한다.
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

            OffsetDateTime receivedAt = CommunicationTime.nowOffset();
            // LWT의 timestamp는 null일 수 있다 — 이 경우 백엔드 수신 시각을 대신 기록한다(5장 4번).
            OffsetDateTime effectiveTimestamp = message.timestamp() != null ? message.timestamp() : receivedAt;

            String commonStatusRaw = IsaacForkliftStatus.fromRaw(message.status())
                    .map(IsaacForkliftStatus::toCommonVehicleStatusRaw)
                    .orElse(message.status());
            VehicleStatusUpdateCommand command = new VehicleStatusUpdateCommand(
                    commonStatusRaw, message.battery(), null, null, null, null, effectiveTimestamp,
                    toIsaacExtras(message));

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
     * Isaac 확장 필드를 DB 저장용 커맨드에 담는다. LWT OFFLINE 메시지처럼 값이 전부 없더라도
     * <b>null이 아닌 {@code IsaacExtras} 객체를 반환</b>한다 — "Isaac 경로에서 온 메시지"라는 사실
     * 자체가 갱신 신호이기 때문이다. 차량이 오프라인이 되어 화물 정보가 사라진 것을 그대로 반영해야
     * 하므로, 여기서 기존 값을 보존하지 않는다({@code VehicleStatusService} 병합 정책 참고).
     */
    private VehicleStatusUpdateCommand.IsaacExtras toIsaacExtras(IsaacForkliftStatusMessage message) {
        IsaacForkliftStatusMessage.Footprint footprint = message.footprint();
        return new VehicleStatusUpdateCommand.IsaacExtras(
                message.forkHeight(),
                message.hasCargo(),
                message.cargoId(),
                footprint != null ? footprint.length() : null,
                footprint != null ? footprint.width() : null);
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

    private IsaacVehicleStatusEventData toEventData(IsaacForkliftStatusMessage message, OffsetDateTime receivedAt) {
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
