package com.fast.backend.isaac.service;

import com.fast.backend.isaac.dto.IsaacVehicleTelemetryMessage;
import com.fast.backend.vehicle.location.VehicleLocationIngestion;
import com.fast.backend.vehicle.location.VehicleLocationIngestionService;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.fast.backend.common.time.CommunicationTime.OFFSET;

/**
 * Isaac Sim telemetry({@code fast/v1/vehicle/{id}/telemetry})를 기존 위치 모델로 바꿔 공통 처리기에 넘긴다.
 *
 * <p>이 클래스가 하는 일은 <b>번역</b>뿐이다.
 * <pre>
 * sim01            → SIM-F01           (VehicleIdAliasResolver)
 * pose.x / pose.y  → position.x / y    (그대로, 단위 m)
 * pose.yaw(radian) → heading(degree)   ([0,360) 정규화는 공통 처리기가)
 * velocity.linear  → speed             (m/s)
 * ts(epoch millis) → messageAt         (UTC 로 해석 후 +09:00 표기)
 * (백엔드 수신 시각) → receivedAt         (공통 처리기가 생성)
 * </pre>
 * DB 갱신·인메모리 갱신·STOMP 발행은 {@link VehicleLocationIngestionService} 가 한다 — 기존 ROS2 경로와
 * 완전히 같은 규칙을 타야 프론트가 두 출처를 구분할 필요가 없다.
 *
 * <p>{@code state}, {@code loaded}, {@code cargoId}는 차량 상태 공통 경로에도 전달해 DB와 상태
 * WebSocket을 갱신한다. {@code forkHeight}, {@code battery}, {@code cargo.h}는 위치 이벤트와 최신
 * 스냅샷에 함께 보존해 프론트가 별도 재조회 없이 즉시 표시한다.
 */
@Service
public class IsaacVehicleTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(IsaacVehicleTelemetryService.class);

    /** 로그에서 어느 계약으로 들어온 위치인지 구분하기 위한 이름. */
    private static final String SOURCE = "isaac-telemetry";

    /** Isaac 은 좌표계를 payload 에 담지 않는다. 맵 좌표로 발행한다는 전제다(§6 고정값). */
    private static final String FRAME_ID = "map";

    /** Isaac 예시 ID({@code C0007}, {@code C-0007})를 내부 숫자 cargo_id로 연결할 때만 사용한다. */
    private static final Pattern ISAAC_CARGO_ID = Pattern.compile("(?i)^C-?0*(\\d+)$");

    private final VehicleIdAliasResolver aliasResolver;
    private final VehicleLocationIngestionService ingestionService;
    private final VehicleStatusService statusService;

    public IsaacVehicleTelemetryService(
            VehicleIdAliasResolver aliasResolver,
            VehicleLocationIngestionService ingestionService,
            VehicleStatusService statusService) {
        this.aliasResolver = aliasResolver;
        this.ingestionService = ingestionService;
        this.statusService = statusService;
    }

    /**
     * @param topicVehicleId 토픽에서 뽑은 ID. payload 의 {@code vehicleId} 와 다르면 경고만 남기고
     *                       <b>payload 값을 신뢰한다</b> — 실제 데이터를 담고 있는 쪽이 payload 다
     */
    public void handleTelemetry(String topicVehicleId, IsaacVehicleTelemetryMessage message) {
        try {
            if (!isValid(message)) {
                return;
            }
            String rawVehicleId = message.vehicleId().trim();
            if (topicVehicleId != null && !topicVehicleId.equals(rawVehicleId)) {
                log.warn("Isaac telemetry vehicle ID mismatch: topicVehicleId={}, payloadVehicleId={}."
                        + " Using the payload value.", topicVehicleId, rawVehicleId);
            }

            Optional<String> normalized = aliasResolver.resolve(rawVehicleId);
            if (normalized.isEmpty()) {
                log.warn("Isaac telemetry discarded: reason=vehicleId is blank after normalization");
                return;
            }
            String vehicleId = normalized.get();
            log.debug("Isaac telemetry received: original={}, normalized={}, ts={}",
                    rawVehicleId, vehicleId, message.ts());

            OffsetDateTime messageAt = toMessageAt(message.ts());
            String reportedCargoId = resolveReportedCargoId(message);
            Boolean reportedLoaded = resolveReportedLoaded(message, reportedCargoId);
            if (Boolean.FALSE.equals(reportedLoaded)) {
                reportedCargoId = null;
            }
            Double reportedCargoHeight = Boolean.FALSE.equals(reportedLoaded)
                    ? null : resolveReportedCargoHeight(message);
            ingestionService.ingest(new VehicleLocationIngestion(
                    vehicleId,
                    message.pose().x(),
                    message.pose().y(),
                    FRAME_ID,
                    toHeadingDegrees(message.pose().yaw()),
                    toSpeed(message.velocity()),
                    messageAt,
                    // 상태는 표시용으로만 싣는다. DB 상태 갱신은 상태 토픽만의 책임이다.
                    IsaacVehicleStateMapper.fromRaw(message.state()),
                    null,
                    finiteOrNull(message.forkHeight()),
                    finiteOrNull(message.battery()),
                    reportedCargoId,
                    message.taskId(),
                    reportedLoaded,
                    reportedCargoHeight), SOURCE);
            updateReportedStatus(
                    vehicleId, message, messageAt, reportedLoaded, reportedCargoId);
        } catch (RuntimeException exception) {
            log.error("Isaac telemetry processing failed and was isolated: vehicleId={}, error={}",
                    message == null ? null : message.vehicleId(), exception.getMessage());
        }
    }

    /**
     * Isaac telemetry가 이미 보내는 state/loaded/cargoId를 차량 현재 상태 정본에 반영한다.
     * 위치 저장과 분리해, 적재 필드 하나가 잘못돼도 미니맵 좌표는 유지한다.
     */
    private void updateReportedStatus(
            String vehicleId,
            IsaacVehicleTelemetryMessage message,
            OffsetDateTime messageAt,
            Boolean hasCargo,
            String reportedCargoId) {
        if (message.state() == null && hasCargo == null && reportedCargoId == null) {
            return;
        }
        Long cargoId = Boolean.FALSE.equals(hasCargo)
                ? null : parseCargoId(reportedCargoId, vehicleId);
        VehicleStatus reportedStatus = IsaacVehicleStateMapper.fromRaw(message.state());
        // 문서 계약 밖 상태는 현재 상태를 UNKNOWN으로 덮지 않고 무시한다.
        String status = message.state() == null || reportedStatus == VehicleStatus.UNKNOWN
                ? null : reportedStatus.name();
        try {
            statusService.updateCurrentStatus(
                    vehicleId, new VehicleStatusUpdateCommand(status, messageAt, hasCargo, cargoId));
        } catch (RuntimeException exception) {
            log.warn("Isaac telemetry status update skipped: vehicleId={}, error={}",
                    vehicleId, exception.getMessage());
        }
    }

    private Long parseCargoId(String rawCargoId, String vehicleId) {
        if (rawCargoId == null || rawCargoId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(rawCargoId.trim());
        } catch (NumberFormatException exception) {
            Matcher matcher = ISAAC_CARGO_ID.matcher(rawCargoId.trim());
            if (matcher.matches()) {
                try {
                    return Long.valueOf(matcher.group(1));
                } catch (NumberFormatException overflow) {
                    // 아래 공통 경고에서 처리한다.
                }
            }
            log.warn("Isaac telemetry cargoId cannot be linked to numeric DB cargo: vehicleId={}, cargoId={}",
                    vehicleId, rawCargoId);
            return null;
        }
    }

    /** 최상위 cargoId가 없으면 신규 cargo 객체의 id를 사용한다. */
    private String resolveReportedCargoId(IsaacVehicleTelemetryMessage message) {
        if (message.cargoId() != null && !message.cargoId().isBlank()) {
            return message.cargoId().trim();
        }
        IsaacVehicleTelemetryMessage.Cargo cargo = message.cargo();
        return cargo == null || cargo.id() == null || cargo.id().isBlank()
                ? null : cargo.id().trim();
    }

    /** 명시적인 loaded가 우선이며, 구형 송신자는 cargo 객체/ID 존재 여부로 보완한다. */
    private Boolean resolveReportedLoaded(
            IsaacVehicleTelemetryMessage message, String reportedCargoId) {
        if (message.loaded() != null) {
            return message.loaded();
        }
        if (message.cargo() != null || reportedCargoId != null) {
            return true;
        }
        return null;
    }

    /** cargo.h는 팔레트를 포함한 Isaac Sim 전체 높이(m)다. AI 실측 높이와 별도로 보존한다. */
    private Double resolveReportedCargoHeight(IsaacVehicleTelemetryMessage message) {
        IsaacVehicleTelemetryMessage.Cargo cargo = message.cargo();
        if (cargo == null || cargo.h() == null || !isFinite(cargo.h()) || cargo.h() <= 0) {
            return null;
        }
        return cargo.h();
    }

    /**
     * 위치를 그릴 수 없게 만드는 값만 거른다.
     *
     * <p>배터리·포크 높이 같은 부가 필드는 검증하지 않는다 — 그것 하나가 잘못됐다고 차량이 미니맵에서
     * 사라지면, 화면을 보는 사람은 차량이 멈춘 것으로 오해한다.
     */
    private boolean isValid(IsaacVehicleTelemetryMessage message) {
        if (message == null) {
            log.warn("Isaac telemetry skipped: message is null");
            return false;
        }
        if (message.vehicleId() == null || message.vehicleId().isBlank()) {
            log.warn("Isaac telemetry skipped: vehicleId is null or blank");
            return false;
        }
        IsaacVehicleTelemetryMessage.Pose pose = message.pose();
        if (pose == null || pose.x() == null || pose.y() == null) {
            log.warn("Isaac telemetry skipped: pose/x/y missing, vehicleId={}", message.vehicleId());
            return false;
        }
        if (!isFinite(pose.x()) || !isFinite(pose.y())) {
            log.warn("Isaac telemetry skipped: pose x/y is NaN or infinite, vehicleId={}",
                    message.vehicleId());
            return false;
        }
        if (pose.yaw() != null && !isFinite(pose.yaw())) {
            log.warn("Isaac telemetry skipped: yaw is NaN or infinite, vehicleId={}", message.vehicleId());
            return false;
        }
        return true;
    }

    /**
     * radian → degree. 정규화([0,360))는 공통 처리기가 한 번만 수행한다.
     *
     * <p>yaw 가 없으면 {@code null} 을 그대로 넘긴다 — 0°(동쪽)로 채우면 "방향을 모른다"와 "동쪽을 본다"가
     * 구분되지 않고, 화면은 차량이 실제로 동쪽을 향한 것처럼 그린다.
     */
    private Double toHeadingDegrees(Double yawRadians) {
        return yawRadians == null ? null : Math.toDegrees(yawRadians);
    }

    /**
     * 선속도를 speed(m/s)로. {@code velocity} 자체가 없으면 {@code null} 이다 — 기존 위치 계약에서
     * {@code speed} 는 nullable 이고, 0 으로 채우면 "정지"라는 사실 주장을 하게 된다.
     */
    private Double toSpeed(IsaacVehicleTelemetryMessage.Velocity velocity) {
        if (velocity == null || velocity.linear() == null || !isFinite(velocity.linear())) {
            return null;
        }
        return velocity.linear();
    }

    /**
     * Isaac 상태 계약을 관제 상태 계약으로 변환한다.
     *
     * <p>공통 enum 에 Isaac 전용 문자열을 넣지 않는다. {@code LOWERING}은 화면의 "포크 승강"
     * 상태({@link VehicleStatus#LIFTING})로, {@code ESTOPPED}는 {@link VehicleStatus#ESTOP}으로
     * 합친다. 나머지 공통 값은 기존 대소문자 방어 규칙을 그대로 쓴다.
     */
    private Double finiteOrNull(Double value) {
        return value != null && isFinite(value) ? value : null;
    }

    /**
     * epoch milliseconds(UTC) → {@code +09:00} {@link OffsetDateTime}.
     *
     * <p>{@code ts} 가 없으면 백엔드 수신 시각으로 대체한다. 여기서 버리면 시각 하나 때문에 좌표를 통째로
     * 잃는데, 구식 메시지 차단은 어차피 {@code updateLocationIfNewer} 가 messageAt 비교로 수행하므로
     * 대체값을 써도 순서 보호가 무너지지 않는다.
     */
    private OffsetDateTime toMessageAt(Long ts) {
        if (ts == null || ts <= 0) {
            log.debug("Isaac telemetry has no usable ts; falling back to the backend receive time");
            return com.fast.backend.common.time.CommunicationTime.nowOffset();
        }
        return Instant.ofEpochMilli(ts).atOffset(OFFSET);
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
