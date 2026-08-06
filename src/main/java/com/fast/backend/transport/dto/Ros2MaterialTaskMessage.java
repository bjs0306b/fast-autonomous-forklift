package com.fast.backend.transport.dto;

/**
 * AI 측정이 끝난 뒤 Isaac/ROS2가 {@code /task}에서 실행할 집기→이동→적재 작업 계획.
 * 좌표는 시뮬 좌표, 높이는 m, yaw는 radian이다.
 */
public record Ros2MaterialTaskMessage(
        String taskId,
        Waypoint pickup,
        Waypoint dropoff
) {
    public record Waypoint(double x, double y, double yaw, double forkHeight) {
    }
}
