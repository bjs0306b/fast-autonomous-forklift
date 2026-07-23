package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * ROS2 차량 위치 메시지를 처리한다(prompt24.md). {@link ForkliftStatusService}와 달리 이 메시지는
 * {@code vehicle_current_status} 테이블에 반영하지 않고 WebSocket으로만 중계한다 — 이유는
 * {@link VehicleLocationEventData} Javadoc 참고(status/location이 같은 행을 공유 upsert하면 서로의
 * 값을 null로 지우는 문제). payload에 상태값이 함께 오더라도 {@link com.fast.backend.vehicle.service.VehicleStatusService}를
 * 호출하지 않는다 — 상태 저장은 오직 상태 토픽·{@link ForkliftStatusService}만의 책임이다(prompt24.md 6장).
 *
 * <p>미등록 차량이면(prompt20.md 11장) 브로드캐스트하지 않고 경고 로그만 남긴다 — 존재 확인은
 * {@link VehicleMapper#existsByVehicleId}로 직접 수행한다({@code VehicleStatusService}를 거치지 않으므로
 * 그 메서드가 주는 예외 기반 존재 확인을 재사용할 수 없다).
 */
@Service
public class ForkliftLocationService {

    private static final Logger log = LoggerFactory.getLogger(ForkliftLocationService.class);

    /** position.frameId가 없을 때의 기본 좌표계(prompt24.md 4장·5장, ROS2/Isaac Sim 실제 규격은 팀 합의 필요). */
    private static final String DEFAULT_FRAME_ID = "map";

    private final VehicleMapper vehicleMapper;
    private final VehicleWebSocketBroadcaster vehicleWebSocketBroadcaster;

    public ForkliftLocationService(VehicleMapper vehicleMapper, VehicleWebSocketBroadcaster vehicleWebSocketBroadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleWebSocketBroadcaster = vehicleWebSocketBroadcaster;
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

            if (!vehicleMapper.existsByVehicleId(message.vehicleId())) {
                log.warn("Vehicle location broadcast skipped, vehicle not registered: vehicleId={}",
                        message.vehicleId());
                return;
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            VehicleLocationEventData data = toEventData(message, receivedAt);
            vehicleWebSocketBroadcaster.broadcastLocation(message.vehicleId(), data, message.messageAt());
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

    private VehicleLocationEventData toEventData(ForkliftLocationMessage message, LocalDateTime receivedAt) {
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

    private String normalizeFrameId(String frameId) {
        return (frameId == null || frameId.isBlank()) ? DEFAULT_FRAME_ID : frameId;
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
