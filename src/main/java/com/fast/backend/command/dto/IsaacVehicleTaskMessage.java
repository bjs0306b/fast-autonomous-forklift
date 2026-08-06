package com.fast.backend.command.dto;

/**
 * Isaac/ROS2가 구독하는 이동 작업 계약.
 *
 * <pre>
 * {
 *   "taskId": "...",
 *   "x": 1.2,
 *   "y": 3.4,
 *   "yaw": 1.5708
 * }
 * </pre>
 *
 * <p>좌표는 meter, {@code yaw}는 radian이다. 기존 명령 계약의 degree heading은 발행 경계에서
 * radian으로 변환한다.
 */
public record IsaacVehicleTaskMessage(
        String taskId,
        double x,
        double y,
        double yaw
) {
}
