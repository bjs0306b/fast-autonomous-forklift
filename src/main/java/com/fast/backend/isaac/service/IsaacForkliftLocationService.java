package com.fast.backend.isaac.service;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.isaac.dto.IsaacForkliftLocationMessage;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.IsaacVehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Isaac Sim 위치 메시지를 처리한다(prompt28.md 3장). 10Hz로 들어올 수 있어 DB에 저장하지 않고
 * WebSocket으로만 중계한다 — 기존 {@code ForkliftLocationService}(ROS2 실물 위치)와 동일한 정책이다.
 *
 * <p>미등록 차량이면 브로드캐스트하지 않고 경고 로그만 남긴다 — 기존 차량 기능과 동일한 정책.
 *
 * <p><b>heading 규격(prompt32.md 1장 5번 확정)</b>: 수신한 {@code heading}(degree)을 [0,360)으로
 * 정규화해서 중계한다. 이전 버전은 {@code direction}(rad)을 정규화 없이 그대로 통과시켰다 — 확정 규격이
 * 단위를 degree로, 정상 범위를 [0,360)으로 정하면서 ROS2 위치 경로와 동일한 정규화를 적용하게 됐다.
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
        // 10Hz 반복 메시지라 INFO로 남기면 로그가 급격히 쌓인다. DEBUG로 낮추고 전체 payload 대신
        // forkliftId·timestamp만 남긴다.
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

            OffsetDateTime receivedAt = CommunicationTime.nowOffset();
            IsaacVehicleLocationEventData data = new IsaacVehicleLocationEventData(
                    message.forkliftId(),
                    message.x(),
                    message.y(),
                    normalizeHeading(message.heading()),
                    message.speed(),
                    message.timestamp(),
                    receivedAt);
            broadcaster.broadcastIsaacLocation(message.forkliftId(), data, message.timestamp());
        } catch (RuntimeException e) {
            log.error("Isaac location broadcast failed unexpectedly: forkliftId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }

    /**
     * 필수값 누락과 NaN/Infinity를 거부한다. heading은 범위를 벗어나도 거부하지 않고 정규화한다
     * (prompt32.md 1장 5번 "범위를 벗어나면 기존 정규화 로직을 사용할 수 있음"). speed 음수는 기존
     * ForkliftLocationService 정책과 맞춰 거부한다.
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
        if (message.heading() == null || !isFinite(message.heading())) {
            log.warn("Isaac location message skipped: heading missing or NaN/infinite, forkliftId={}", forkliftId);
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

    /** heading(degree)을 [0,360) 범위로 정규화한다(예: -90 → 270, 450 → 90). */
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

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
