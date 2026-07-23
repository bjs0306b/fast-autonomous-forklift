package com.fast.backend.isaac.service;

import com.fast.backend.isaac.dto.IsaacForkliftLocationMessage;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.IsaacVehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Isaac Sim 위치 메시지를 처리한다(prompt28.md 3장). 10Hz로 들어올 수 있어 DB에 저장하지 않고
 * WebSocket으로만 중계한다 — 기존 {@code ForkliftLocationService}(ROS2 실물 위치)와 동일한 정책이다
 * (요구사항 3장 6번 "기존 정책이 위치를 실시간 WebSocket 전달만 한다면 그대로 유지한다").
 *
 * <p>미등록 차량이면 브로드캐스트하지 않고 경고 로그만 남긴다 — 기존 차량 기능과 동일한 정책
 * (요구사항 3장 2번 "등록되지 않은 차량 처리 정책을 기존 차량 기능과 맞춘다").
 */
@Service
public class IsaacForkliftLocationService {

    private static final Logger log = LoggerFactory.getLogger(IsaacForkliftLocationService.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public IsaacForkliftLocationService(VehicleMapper vehicleMapper, VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.broadcaster = broadcaster;
    }

    public void handleLocation(IsaacForkliftLocationMessage message) {
        // 10Hz 반복 메시지라 INFO로 남기면 로그가 급격히 쌓인다(요구사항 3장 4번). DEBUG로 낮추고
        // 전체 payload 대신 forkliftId·timestamp만 남긴다.
        log.debug("Isaac forklift location message received: forkliftId={}, timestamp={}",
                message.forkliftId(), message.timestamp());

        try {
            if (!isValid(message)) {
                return;
            }
            if (!vehicleMapper.existsByVehicleId(message.forkliftId())) {
                log.warn("Isaac location broadcast skipped, vehicle not registered: forkliftId={}",
                        message.forkliftId());
                return;
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            IsaacVehicleLocationEventData data = new IsaacVehicleLocationEventData(
                    message.forkliftId(), message.x(), message.y(), message.direction(), message.speed(),
                    message.timestamp(), receivedAt);
            broadcaster.broadcastIsaacLocation(message.forkliftId(), data, message.timestamp());
        } catch (RuntimeException e) {
            log.error("Isaac location broadcast failed unexpectedly: forkliftId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }

    /**
     * 필수값 누락과 NaN/Infinity를 거부한다(prompt28.md 3장 필드 규격). direction의 -π~π 범위는
     * "원칙적으로"라고만 돼 있어(강제 규칙 아님) 범위 검증이나 정규화를 하지 않는다 — 합의된 rad 단위
     * 원본값을 그대로 보존한다. speed 음수는 기존 ForkliftLocationService 정책과 맞춰 거부한다.
     */
    private boolean isValid(IsaacForkliftLocationMessage message) {
        String forkliftId = message.forkliftId();
        if (forkliftId == null || forkliftId.isBlank()) {
            log.warn("Isaac location message skipped: forkliftId is null or blank");
            return false;
        }
        if (message.timestamp() == null) {
            log.warn("Isaac location message skipped: timestamp is null, forkliftId={}", forkliftId);
            return false;
        }
        if (message.x() == null || message.y() == null) {
            log.warn("Isaac location message skipped: x/y missing, forkliftId={}", forkliftId);
            return false;
        }
        if (!isFinite(message.x()) || !isFinite(message.y())) {
            log.warn("Isaac location message skipped: x/y is NaN or infinite, forkliftId={}", forkliftId);
            return false;
        }
        if (message.direction() == null || !isFinite(message.direction())) {
            log.warn("Isaac location message skipped: direction missing or NaN/infinite, forkliftId={}", forkliftId);
            return false;
        }
        if (message.speed() == null || !isFinite(message.speed())) {
            log.warn("Isaac location message skipped: speed missing or NaN/infinite, forkliftId={}", forkliftId);
            return false;
        }
        if (message.speed() < 0) {
            log.warn("Isaac location message skipped: speed is negative, forkliftId={}, speed={}",
                    forkliftId, message.speed());
            return false;
        }
        return true;
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
