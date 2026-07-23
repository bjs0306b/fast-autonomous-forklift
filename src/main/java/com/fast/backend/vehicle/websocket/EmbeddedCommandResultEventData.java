package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code VEHICLE_COMMAND_RESULT_UPDATED} 이벤트의 {@code data} payload — 실물(REAL01) 명령 결과 전용
 * (prompt29.md 7장·19장). 기존 {@link VehicleCommandResultEventData}(commandId/vehicleId/command/
 * resultStatus/message/completedAt 6필드)로는 {@code forkState}/{@code limitBottom}/
 * {@code emergencyStopApplied}/{@code stoppedActions}/{@code requiresReset}/{@code errorCode}를 표현할
 * 수 없어(특히 비상정지 결과의 핵심 정보가 전부 빠짐) 별도 클래스로 추가했다(19장 "표현할 수 없다면
 * 기존 구조를 깨지 않고 추가"). eventType은 기존 {@code VEHICLE_COMMAND_RESULT_UPDATED}를 그대로
 * 재사용한다 — 비상정지 결과도 이 하나의 이벤트 타입 안에서 {@code emergencyStopApplied}/
 * {@code requiresReset} 필드로 충분히 표현되므로 별도 이벤트 타입을 만들지 않았다(19장 근거).
 */
public record EmbeddedCommandResultEventData(
        String commandId,
        String forkliftId,
        String command,
        String result,
        String forkState,
        Boolean limitBottom,
        Boolean emergencyStopApplied,
        List<String> stoppedActions,
        Boolean requiresReset,
        String errorCode,
        String message,
        LocalDateTime completedAt
) {
}
