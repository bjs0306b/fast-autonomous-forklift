package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fast.backend.common.time.CommunicationTime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * ROS2 차량 위치 메시지를 처리한다(prompt24.md → prompt83에서 DB 저장 추가).
 *
 * <p>처리 순서: 검증 → 등록 차량 확인 → stale 확인 → {@code vehicle_current_status} <b>위치 컬럼만</b>
 * 갱신 → 최신 위치 스냅샷 갱신 → WebSocket 브로드캐스트.
 *
 * <p><b>위치 저장에 {@code upsert}를 쓰지 않는 이유</b>(prompt83 14항): 그 메서드는 전체 상태 한 벌을
 * 쓰기 때문에 위치 메시지에 없는 battery·speed·Isaac 확장 필드를 null로 지운다 — 예전에 이 클래스가
 * DB를 아예 건드리지 않았던 이유가 바로 그것이다({@link VehicleLocationEventData} Javadoc 참고).
 * 이제는 위치 전용 부분 갱신({@link VehicleCurrentStatusMapper#updateLocation})을 써서 그 충돌 없이
 * 저장한다. 상태값 저장은 여전히 이 클래스의 책임이 아니다 — payload에 status가 실려 와도
 * {@code VehicleStatusService}를 호출하지 않는다(prompt24.md 6장).
 *
 * <p><b>{@code vehicle_status_history}는 남기지 않는다</b>(prompt83 19항). 이력은 상태 토픽 경로에서만
 * 생성하는 것이 기존 설계이며(prompt22.md 4장), 5Hz로 들어오는 위치 메시지마다 이력을 쌓으면 테이블이
 * 하루 40만 행 규모로 커진다. 기존 동작을 그대로 유지한다.
 *
 * <p>미등록 차량이면(prompt20.md 11장) 저장·브로드캐스트하지 않고 경고 로그만 남긴다 — 존재 확인은
 * {@link VehicleMapper#existsByVehicleId}로 직접 수행한다({@code VehicleStatusService}를 거치지 않으므로
 * 그 메서드가 주는 예외 기반 존재 확인을 재사용할 수 없다).
 */
@Service
public class ForkliftLocationService {

    private static final Logger log = LoggerFactory.getLogger(ForkliftLocationService.class);

    /**
     * 유일하게 허용되는 좌표계(prompt84 3·4항 최종 합의). frameId 판단은 이 상수와
     * {@link #isSupportedFrameId(String)} <b>한 곳에서만</b> 한다 — 나중에 다른 좌표계를 지원하게
     * 되면 여기만 고친다.
     *
     * <p>이전에는 {@code odom}을 "중계는 하되 저장은 안 함"으로 두었는데, 최종 합의에서
     * <b>중계도 하지 않는 규격 위반</b>으로 바뀌었다. odom은 주행거리계 기준 상대 좌표라 전역 지도
     * 좌표로 해석하면 차량이 엉뚱한 곳에 그려진다 — DB든 화면이든 마찬가지다.
     *
     * <p>frameId 생략(null/blank)도 <b>map으로 자동 보정하지 않는다</b>. 합의 payload에서 frameId는
     * 필수이며, 누락을 조용히 map으로 채우면 발행 측 규격 위반이 드러나지 않는다.
     */
    private static final String SUPPORTED_FRAME_ID = "map";

    /**
     * 허용 vehicleId 형태(prompt83 7·8항). 하이픈으로 이어진 영숫자 세그먼트만 받는다 — 확정 규격은
     * {@code REAL-F01}이고 {@code REAL_F01}(underscore)은 <b>잘못된 규격</b>이다.
     *
     * <p>underscore를 하이픈으로 조용히 바꾸지 않는다. 자동 변환하면 잘못된 규격으로 발행하는 쪽이
     * 그 사실을 영영 모르고, 나중에 두 표기가 섞인 데이터가 남는다.
     */
    private static final Pattern VEHICLE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+(-[A-Za-z0-9]+)*$");

    /** ROS2 실물 위치의 출처 태그(prompt50.md 6·14장). */
    private static final String SOURCE_REAL = "REAL";

    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    private final VehicleWebSocketBroadcaster vehicleWebSocketBroadcaster;
    private final LatestVehicleLocationProvider latestVehicleLocationProvider;

    public ForkliftLocationService(
            VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper vehicleCurrentStatusMapper,
            VehicleWebSocketBroadcaster vehicleWebSocketBroadcaster,
            LatestVehicleLocationProvider latestVehicleLocationProvider) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
        this.vehicleWebSocketBroadcaster = vehicleWebSocketBroadcaster;
        this.latestVehicleLocationProvider = latestVehicleLocationProvider;
    }

    /**
     * 토픽을 알 수 없는 호출자용(테스트·재처리). 로그의 topic 자리에는 {@code -}가 찍힌다.
     * MQTT 수신 경로는 반드시 {@link #handleLocation(String, ForkliftLocationMessage)}를 쓴다.
     */
    public void handleLocation(ForkliftLocationMessage message) {
        handleLocation(null, message);
    }

    public void handleLocation(String topic, ForkliftLocationMessage message) {
        // ROS2가 5Hz(0.2초 간격)로 보내는 반복 메시지라 INFO로 매번 남기면 로그가 급격히 쌓인다
        // (prompt25.md 1.3장·8장·최종 주의사항 "매 위치 메시지마다 INFO 로그를 남기지 마"). DEBUG로
        // 낮추고, 전체 payload 대신 vehicleId·messageAt만 남긴다(prompt24.md 8장 정책은 그대로 유지).
        log.debug("[VehicleLocation] received topic={}, vehicleId={}, x={}, y={}, frameId={}, heading={}, messageAt={}",
                topicOrDash(topic), message.vehicleId(),
                message.position() == null ? null : message.position().x(),
                message.position() == null ? null : message.position().y(),
                message.position() == null ? null : message.position().frameId(),
                message.heading(), message.messageAt());

        try {
            if (!isValid(topic, message)) {
                return;
            }

            if (!vehicleMapper.existsByVehicleId(message.vehicleId())) {
                // 폐기 사유와 messageAt을 함께 남긴다(prompt73 3.3장) — "왜 화면에 안 뜨지"를 추적할 때
                // vehicleId만으로는 등록 누락인지 다른 원인인지 구분되지 않는다.
                // topic/topicVehicleId는 MqttMessageRouter가 대조 단계에서 이미 남긴다.
                log.warn("[VehicleLocation] rejected reason=vehicle not registered, topic={}, vehicleId={}, messageAt={}",
                        topicOrDash(topic), message.vehicleId(), message.messageAt());
                return;
            }

            OffsetDateTime receivedAt = CommunicationTime.nowOffset();
            // 저장된 message_at 과 절대시각으로 비교해 stale/중복을 먼저 거른다(prompt86 A안).
            // 통과해도 SQL 이 같은 조건을 한 번 더 본다 — 동시 수신 시 둘 다 통과할 수 있어서다.
            if (!persistLocation(topic, message, receivedAt)) {
                return;
            }
            // 최신 위치 스냅샷(대시보드 REST 용). Provider 도 자체 stale 가드를 갖는다.
            latestVehicleLocationProvider.update(toSnapshot(message, receivedAt));
            VehicleLocationEventData data = toEventData(message, receivedAt);
            vehicleWebSocketBroadcaster.broadcastLocation(message.vehicleId(), data, message.messageAt());
        } catch (RuntimeException e) {
            log.error("Vehicle location broadcast failed unexpectedly: vehicleId={}, error={}",
                    message.vehicleId(), e.getMessage());
        }
    }

    /**
     * {@code vehicle_current_status}의 위치 컬럼만 갱신한다(prompt83·84 → prompt86 A안).
     *
     * <p><b>stale 비교는 절대시각(Instant) 기준</b>이다. 수신 {@code messageAt} 은 오프셋을 가진
     * {@link OffsetDateTime} 이고 DB {@code message_at} 은 오프셋을 담지 못하는 {@code DATETIME}
     * (= Asia/Seoul 벽시계, {@link CommunicationTime} 정책)이라, 둘을 같은 절대시각으로 환산해야
     * {@code +09:00} 과 {@code Z} 표기가 섞여도 같은 순간을 같게 판단한다. 시스템 기본 타임존에
     * 의존하지 않도록 {@link CommunicationTime#ZONE} 을 명시적으로 적용한다.
     *
     * <p>비교 규칙: stored 없음/null → 저장 / incoming &gt; stored → 저장 /
     * incoming == stored → QoS 1 재전송으로 무시 / incoming &lt; stored → stale 로 무시.
     *
     * <p>여기서 거르는 것은 "불필요한 write 를 줄이고 왜 무시됐는지 로그를 남기기" 위해서다.
     * 최종 방어선은 {@code updateLocation} SQL 안의 같은 조건이다(동시 수신 대비).
     *
     * @return DB 반영을 시도했으면 true, stale/중복이라 건너뛰었으면 false
     */
    private boolean persistLocation(String topic, ForkliftLocationMessage message, OffsetDateTime receivedAt) {
        String vehicleId = message.vehicleId();
        LocalDateTime messageAt = CommunicationTime.toLocal(message.messageAt());
        LocalDateTime storedMessageAt = vehicleCurrentStatusMapper.findByVehicleId(vehicleId)
                .map(VehicleCurrentStatus::getMessageAt)
                .orElse(null);

        if (storedMessageAt != null) {
            Instant incomingInstant = message.messageAt().toInstant();
            Instant storedInstant = storedMessageAt.atZone(CommunicationTime.ZONE).toInstant();
            if (!incomingInstant.isAfter(storedInstant)) {
                String reason = incomingInstant.equals(storedInstant)
                        ? "DUPLICATE_MESSAGE_AT" : "STALE_MESSAGE_AT";
                log.warn("[VehicleLocation] rejected reason={}, vehicleId={}, incomingMessageAt={}, "
                                + "incomingInstant={}, storedMessageAt={}, storedInstant={}, topic={}",
                        reason, vehicleId, message.messageAt(), incomingInstant,
                        storedMessageAt, storedInstant, topicOrDash(topic));
                return false;
            }
        }

        LocalDateTime now = CommunicationTime.toLocal(receivedAt);
        Double heading = normalizeHeading(message.heading());
        // 반환값(row count)으로 성공 여부를 판단하지 않는다 — MySQL 설정(CLIENT_FOUND_ROWS)에 따라
        // "변경된 행 수"와 "매칭된 행 수" 중 무엇이 오는지 달라진다.
        vehicleCurrentStatusMapper.updateLocation(
                vehicleId, message.position().x(), message.position().y(), heading, messageAt, now);

        log.info("[VehicleLocation] updated vehicleId={}, x={}, y={}, heading={}, messageAt={}",
                vehicleId, message.position().x(), message.position().y(), heading, messageAt);
        return true;
    }

    private String topicOrDash(String topic) {
        return (topic == null || topic.isBlank()) ? "-" : topic;
    }

    /**
     * 필수값(vehicleId, position.x, position.y, messageAt) 누락, NaN/Infinity 좌표, 불완전한 quaternion,
     * 음수/비유한 speed를 걸러낸다(prompt24.md 5장). 하나라도 위반하면 메시지 전체를 버리고 브로드캐스트하지
     * 않는다 — 위치 절반만, 방향 없이 broadcast하는 것보다 다음 정상 메시지를 기다리는 편이 안전하다.
     */
    private boolean isValid(String topic, ForkliftLocationMessage message) {
        String vehicleId = message.vehicleId();
        if (vehicleId == null || vehicleId.isBlank()) {
            log.warn("Vehicle location message skipped: vehicleId is null or blank");
            return false;
        }
        if (vehicleId.indexOf('_') >= 0) {
            // 자동 변환하지 않는다(prompt83 8항) — REAL_F01 → REAL-F01로 조용히 고치면 발행 측이
            // 규격 위반을 계속 모른 채로 두 표기가 섞인 데이터가 쌓인다.
            log.warn("[VehicleLocation] rejected reason=vehicleId uses underscore (expected hyphen form like REAL-F01), "
                    + "topic={}, vehicleId={}", topicOrDash(topic), vehicleId);
            return false;
        }
        if (!VEHICLE_ID_PATTERN.matcher(vehicleId).matches()) {
            log.warn("[VehicleLocation] rejected reason=vehicleId format invalid, topic={}, vehicleId={}",
                    topicOrDash(topic), vehicleId);
            return false;
        }
        if (message.messageAt() == null) {
            log.warn("Vehicle location message skipped: messageAt is null, vehicleId={}", vehicleId);
            return false;
        }

        ForkliftLocationMessage.Position position = message.position();
        if (position == null || position.x() == null || position.y() == null) {
            log.warn("Vehicle location message skipped: position/x/y missing, vehicleId={}", vehicleId);
            return false;
        }
        if (!isFinite(position.x()) || !isFinite(position.y())) {
            log.warn("Vehicle location message skipped: position x/y is NaN or infinite, vehicleId={}", vehicleId);
            return false;
        }
        if (!isSupportedFrameId(position.frameId())) {
            log.warn("[VehicleLocation] rejected reason=UNSUPPORTED_FRAME_ID, topic={}, vehicleId={}, "
                            + "frameId={}, expectedFrameId={}",
                    topicOrDash(topic), vehicleId, position.frameId(), SUPPORTED_FRAME_ID);
            return false;
        }

        if (message.heading() != null && !isFinite(message.heading())) {
            log.warn("Vehicle location message skipped: heading is NaN or infinite, vehicleId={}", vehicleId);
            return false;
        }

        if (!isQuaternionValid(message.quaternion())) {
            log.warn("Vehicle location message skipped: quaternion is incomplete or invalid, vehicleId={}", vehicleId);
            return false;
        }

        Double speed = message.speed();
        if (speed != null) {
            if (!isFinite(speed)) {
                log.warn("Vehicle location message skipped: speed is NaN or infinite, vehicleId={}", vehicleId);
                return false;
            }
            if (speed < 0) {
                log.warn("Vehicle location message skipped: speed is negative, vehicleId={}, speed={}",
                        vehicleId, speed);
                return false;
            }
        }

        return true;
    }

    /**
     * quaternion 객체 자체가 없거나 네 값이 모두 null이면 "quaternion 없음"으로 허용한다(유효).
     * 일부만 채워진 경우는 불완전한 값이므로 거부한다(prompt24.md 5장).
     */
    private boolean isQuaternionValid(ForkliftLocationMessage.Quaternion quaternion) {
        if (quaternion == null) {
            return true;
        }
        int presentCount = countNonNull(quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
        if (presentCount == 0) {
            return true;
        }
        if (presentCount < 4) {
            return false;
        }
        return isFinite(quaternion.x()) && isFinite(quaternion.y())
                && isFinite(quaternion.z()) && isFinite(quaternion.w());
    }

    private int countNonNull(Double... values) {
        int count = 0;
        for (Double value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private VehicleLocationSnapshot toSnapshot(ForkliftLocationMessage message, OffsetDateTime receivedAt) {
        return new VehicleLocationSnapshot(
                message.vehicleId(),
                SOURCE_REAL,
                message.position().x(),
                message.position().y(),
                normalizeHeading(message.heading()),
                message.speed(),
                normalizeFrameId(message.position().frameId()),
                message.messageAt(),
                receivedAt);
    }

    private VehicleLocationEventData toEventData(ForkliftLocationMessage message, OffsetDateTime receivedAt) {
        VehicleStatus status = VehicleStatus.fromRaw(message.status());

        VehicleLocationEventData.Position position = new VehicleLocationEventData.Position(
                message.position().x(),
                message.position().y(),
                normalizeFrameId(message.position().frameId()));

        return new VehicleLocationEventData(
                message.vehicleId(),
                status,
                position,
                normalizeHeading(message.heading()),
                toEventQuaternion(message.quaternion()),
                message.speed(),
                message.messageAt(),
                receivedAt);
    }

    /**
     * frameId 판정의 <b>유일한</b> 지점(prompt84 4항). null/blank도 위반이다 — 생략을 map으로
     * 보정하지 않는다.
     */
    private boolean isSupportedFrameId(String frameId) {
        return frameId != null && SUPPORTED_FRAME_ID.equals(frameId.trim());
    }

    /**
     * 검증을 통과한 메시지의 frameId는 항상 {@link #SUPPORTED_FRAME_ID}다. 앞뒤 공백만 정리해
     * 내려보낸다 — 여기서 다시 판정하지 않는다(중복 검증 금지).
     */
    private String normalizeFrameId(String frameId) {
        return frameId.trim();
    }

    /**
     * heading(degree)을 [0, 360) 범위로 정규화한다(예: -90 → 270, 450 → 90, prompt24.md 5장). 이
     * 프로젝트에 원본값 보존을 요구하는 기존 소비자가 없어(위와 동일 이유) 정규화 정책을 선택했다.
     */
    private Double normalizeHeading(Double heading) {
        if (heading == null) {
            return null;
        }
        double normalized = heading % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return normalized;
    }

    /** 네 값이 모두 null인 quaternion은 "없음"으로 취급해 null을 반환한다(prompt24.md 5장). */
    private VehicleLocationEventData.Quaternion toEventQuaternion(ForkliftLocationMessage.Quaternion quaternion) {
        if (quaternion == null) {
            return null;
        }
        if (countNonNull(quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w()) == 0) {
            return null;
        }
        return new VehicleLocationEventData.Quaternion(
                quaternion.x(), quaternion.y(), quaternion.z(), quaternion.w());
    }
}
