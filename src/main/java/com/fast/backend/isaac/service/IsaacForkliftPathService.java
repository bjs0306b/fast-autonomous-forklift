package com.fast.backend.isaac.service;

import com.fast.backend.isaac.dto.IsaacForkliftPathMessage;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import com.fast.backend.vehicle.websocket.VehiclePathEventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Isaac Sim 경로 메시지를 처리한다(prompt28.md 6장). 관제 화면 표시 목적이라 경로 이력을 DB에 저장하지
 * 않고 실시간 WebSocket 전달만 한다(13장 "이번 작업에서 무조건 경로 이력 테이블을 만들지 않는다").
 */
@Service
public class IsaacForkliftPathService {

    private static final Logger log = LoggerFactory.getLogger(IsaacForkliftPathService.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public IsaacForkliftPathService(VehicleMapper vehicleMapper, VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.broadcaster = broadcaster;
    }

    public void handlePath(IsaacForkliftPathMessage message) {
        log.debug("Isaac forklift path message received: forkliftId={}, timestamp={}",
                message.forkliftId(), message.timestamp());

        try {
            if (!isValid(message)) {
                return;
            }
            if (!vehicleMapper.existsByVehicleId(message.forkliftId())) {
                log.warn("Isaac path broadcast skipped, vehicle not registered: forkliftId={}", message.forkliftId());
                return;
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            VehiclePathEventData data = toEventData(message, receivedAt);
            broadcaster.broadcastPath(message.forkliftId(), data, message.timestamp());
        } catch (RuntimeException e) {
            log.error("Isaac path broadcast failed unexpectedly: forkliftId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }

    /**
     * waypoints는 null을 금지하되 빈 배열은 허용한다 — 목표점이 직선으로 바로 도달 가능하면 중간
     * 경유점 없이 goal만 있는 경로도 유효한 사용 목적이라고 판단했다(6장 "빈 배열 허용 여부는 실제
     * 사용 목적을 검토한다"). goal은 null을 금지하고 좌표·방향 모두 필수로 검증한다.
     */
    private boolean isValid(IsaacForkliftPathMessage message) {
        String forkliftId = message.forkliftId();
        if (forkliftId == null || forkliftId.isBlank()) {
            log.warn("Isaac path message skipped: forkliftId is null or blank");
            return false;
        }
        if (message.timestamp() == null) {
            log.warn("Isaac path message skipped: timestamp is null, forkliftId={}", forkliftId);
            return false;
        }
        if (message.waypoints() == null) {
            log.warn("Isaac path message skipped: waypoints is null, forkliftId={}", forkliftId);
            return false;
        }
        for (IsaacForkliftPathMessage.Waypoint waypoint : message.waypoints()) {
            if (waypoint == null || waypoint.x() == null || waypoint.y() == null
                    || !isFinite(waypoint.x()) || !isFinite(waypoint.y())) {
                log.warn("Isaac path message skipped: invalid waypoint, forkliftId={}", forkliftId);
                return false;
            }
        }
        IsaacForkliftPathMessage.Goal goal = message.goal();
        if (goal == null) {
            log.warn("Isaac path message skipped: goal is null, forkliftId={}", forkliftId);
            return false;
        }
        if (goal.x() == null || goal.y() == null || !isFinite(goal.x()) || !isFinite(goal.y())) {
            log.warn("Isaac path message skipped: goal x/y invalid, forkliftId={}", forkliftId);
            return false;
        }
        if (goal.direction() == null || !isFinite(goal.direction())) {
            log.warn("Isaac path message skipped: goal direction invalid, forkliftId={}", forkliftId);
            return false;
        }
        return true;
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private VehiclePathEventData toEventData(IsaacForkliftPathMessage message, LocalDateTime receivedAt) {
        List<VehiclePathEventData.Waypoint> waypoints = new ArrayList<>();
        for (IsaacForkliftPathMessage.Waypoint waypoint : message.waypoints()) {
            waypoints.add(new VehiclePathEventData.Waypoint(waypoint.x(), waypoint.y()));
        }
        VehiclePathEventData.Goal goal = new VehiclePathEventData.Goal(
                message.goal().x(), message.goal().y(), message.goal().direction());
        return new VehiclePathEventData(message.forkliftId(), waypoints, goal, message.timestamp(), receivedAt);
    }
}
