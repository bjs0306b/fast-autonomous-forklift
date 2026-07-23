package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code VEHICLE_PATH_UPDATED} 이벤트의 {@code data} payload — Isaac Sim 경로 메시지 전용
 * (prompt28.md 6장·12장). 경로 이력을 DB에 저장하지 않고(13장) 실시간 WebSocket 전달만 하므로,
 * 이 레코드가 경로 데이터가 프론트로 전달되는 유일한 통로다.
 */
public record VehiclePathEventData(
        String forkliftId,
        List<Waypoint> waypoints,
        Goal goal,
        LocalDateTime timestamp,
        LocalDateTime receivedAt
) {

    public record Waypoint(Double x, Double y) {
    }

    public record Goal(Double x, Double y, Double direction) {
    }
}
