package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.location.VehicleLocationIngestion;
import com.fast.backend.vehicle.location.VehicleLocationIngestionService;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * ROS2 차량 위치 메시지를 처리한다(prompt24.md). {@link ForkliftStatusService}와 달리 이 메시지는
 * {@code vehicle_current_status} 테이블에 반영하지 않고 WebSocket으로만 중계한다 — 이유는
 * {@link VehicleLocationEventData} Javadoc 참고(status/location이 같은 행을 공유 upsert하면 서로의
 * 값을 null로 지우는 문제). payload에 상태값이 함께 오더라도 {@link com.fast.backend.vehicle.service.VehicleStatusService}를
 * 호출하지 않는다 — 상태 저장은 오직 상태 토픽·{@link ForkliftStatusService}만의 책임이다(prompt24.md 6장).
 *
 * <p>미등록 차량 폐기·구식 메시지 차단·DB 갱신·인메모리 갱신·브로드캐스트는 이 클래스가 직접 하지 않고
 * {@link VehicleLocationIngestionService} 가 맡는다. Isaac Sim telemetry 도 같은 처리기를 쓰므로,
 * <b>이 클래스에 남은 책임은 ROS2 스키마 검증과 변환뿐</b>이다.
 */
@Service
public class ForkliftLocationService {

    private static final Logger log = LoggerFactory.getLogger(ForkliftLocationService.class);

    /**
     * 허용 frameId(prompt32.md 1장 5번 확정). {@code map}은 전역 지도 좌표계, {@code odom}은 주행거리계
     * 기준 좌표계다. Isaac과 ROS2는 <b>동일한 원점</b>을 사용한다고 가정한다.
     *
     * <p>그 외 frameId는 <b>메시지를 폐기</b>하고 경고 로그를 남긴다(확정 규격이 "validation 실패 또는
     * 명확한 경고 로그"를 요구했고, 이 클래스는 이미 좌표·quaternion 위반을 전부 폐기로 처리하고 있어
     * 같은 정책을 유지하는 편이 일관적이다). 알 수 없는 좌표계의 위치를 관제 화면에 그리면 차량이 엉뚱한
     * 곳에 표시되므로, 다음 정상 메시지를 기다리는 편이 안전하다.
     */
    private static final Set<String> ALLOWED_FRAME_IDS = Set.of("map", "odom");

    /** 로그에서 어느 계약으로 들어온 위치인지 구분하기 위한 이름. */
    private static final String SOURCE = "ros2-location";

    private final VehicleLocationIngestionService ingestionService;

    public ForkliftLocationService(VehicleLocationIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public void handleLocation(ForkliftLocationMessage message) {
        // ROS2가 5Hz(0.2초 간격)로 보내는 반복 메시지라 INFO로 매번 남기면 로그가 급격히 쌓인다
        // (prompt25.md 1.3장·8장·최종 주의사항 "매 위치 메시지마다 INFO 로그를 남기지 마"). DEBUG로
        // 낮추고, 전체 payload 대신 vehicleId·messageAt만 남긴다(prompt24.md 8장 정책은 그대로 유지).
        log.debug("Forklift location message received: vehicleId={}, messageAt={}",
                message.vehicleId(), message.messageAt());

        try {
            if (!isValid(message)) {
                return;
            }
            // 검증까지가 이 클래스의 책임이다. DB·인메모리·브로드캐스트는 공통 처리기가 한 벌만 갖는다.
            ingestionService.ingest(toIngestion(message), SOURCE);
        } catch (RuntimeException e) {
            log.error("Vehicle location broadcast failed unexpectedly: vehicleId={}, error={}",
                    message.vehicleId(), e.getMessage());
        }
    }

    /**
     * 필수값(vehicleId, position.x, position.y, messageAt) 누락, NaN/Infinity 좌표, 불완전한 quaternion,
     * 음수/비유한 speed를 걸러낸다(prompt24.md 5장). 하나라도 위반하면 메시지 전체를 버리고 브로드캐스트하지
     * 않는다 — 위치 절반만, 방향 없이 broadcast하는 것보다 다음 정상 메시지를 기다리는 편이 안전하다.
     */
    private boolean isValid(ForkliftLocationMessage message) {
        String vehicleId = message.vehicleId();
        if (vehicleId == null || vehicleId.isBlank()) {
            log.warn("Vehicle location message skipped: vehicleId is null or blank");
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
        if (!isFrameIdAllowed(position.frameId())) {
            log.warn("Vehicle location message skipped: unsupported frameId (allowed: map, odom), "
                    + "vehicleId={}, frameId={}", vehicleId, position.frameId());
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

    private VehicleLocationIngestion toIngestion(ForkliftLocationMessage message) {
        return new VehicleLocationIngestion(
                message.vehicleId(),
                message.position().x(),
                message.position().y(),
                message.position().frameId(),
                message.heading(),
                message.speed(),
                message.messageAt(),
                VehicleStatus.fromRaw(message.status()),
                toEventQuaternion(message.quaternion()));
    }

    /** 생략(null/빈 값)은 기본값 {@code map}으로 허용하고, 그 외에는 허용 목록에 있어야 한다. */
    private boolean isFrameIdAllowed(String frameId) {
        if (frameId == null || frameId.isBlank()) {
            return true;
        }
        return ALLOWED_FRAME_IDS.contains(frameId.trim());
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
