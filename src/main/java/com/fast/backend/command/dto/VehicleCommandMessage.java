package com.fast.backend.command.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;

import java.time.OffsetDateTime;

/**
 * 백엔드가 {@code forklift/{vehicleId}/command} 토픽으로 발행하는 모든 명령의 공통 envelope.
 *
 * <pre>
 * {
 *   "commandId": "CMD-001",
 *   "vehicleId": "REAL-F01",
 *   "targetSystem": "ROS2",
 *   "commandCategory": "MOVE",
 *   "command": "MOVE",
 *   "payload": { "destination": { "x": 5.0, "y": 6.0, "heading": 180.0, "frameId": "map" } },
 *   "timestamp": "2026-07-23T11:20:27+09:00"
 * }
 * </pre>
 *
 * <p>필수 필드: {@code commandId}, {@code vehicleId}, {@code targetSystem}, {@code commandCategory},
 * {@code command}, {@code timestamp}. 선택 필드: {@code payload}.
 *
 * <p>{@code timestamp}와 {@code commandId}는 백엔드가 생성한다.
 */
public record VehicleCommandMessage(
        String commandId,
        String vehicleId,
        VehicleCommandTargetSystem targetSystem,
        VehicleCommandCategory commandCategory,
        VehicleCommandType command,
        VehicleCommandPayload payload,
        OffsetDateTime timestamp
) {

    /**
     * 기존 Isaac Sim 브리지가 읽는 과도기 호환 목적지다.
     * ROS2는 {@code payload.destination}을 읽고 이 필드는 무시한다.
     */
    @JsonProperty("destination")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public LegacyDestination legacyDestination() {
        VehicleCommandDestination destination = payload == null ? null : payload.destination();
        if (destination == null) {
            return null;
        }
        double direction = destination.heading() == null
                ? 0.0
                : Math.toRadians(destination.heading());
        return new LegacyDestination(destination.x(), destination.y(), direction);
    }

    /** Isaac Sim 구형 계약은 방향을 radian으로 사용한다. */
    public record LegacyDestination(Double x, Double y, double direction) {
    }
}
