package com.fast.backend.command.websocket;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code VEHICLE_COMMAND_RESULT_UPDATED} 이벤트의 {@code data} payload
 * (구 {@code EmbeddedCommandResultEventData} + 구 {@code VehicleCommandResultEventData} 통합).
 *
 * <p>이전에는 실물 임베디드 결과용과 (사용되지 않던) 범용 결과용 두 개의 EventData가 있었다. 명령
 * 자체가 하나의 envelope로 통합됐으므로(prompt32.md 1장 12번) 결과 payload도 하나로 합쳤다.
 *
 * <p>{@code targetSystem}/{@code commandCategory}는 어떤 시스템이 응답했는지 프론트가 구분할 수 있도록
 * 포함한다. 구 형식으로 수신된 결과에서는 백엔드가 <b>발행 당시 저장해 둔 값</b>으로 채워 넣으므로
 * 항상 non-null이다.
 *
 * <p>{@code forkState}/{@code limitBottom}/{@code emergencyStopApplied}/{@code stoppedActions}/
 * {@code requiresReset}은 임베디드 결과에만 있는 선택 정보다 — 특히 비상정지 결과의 핵심이라 확정 규격
 * 예시에 없더라도 유지한다.
 */
public record VehicleCommandResultEventData(
        String commandId,
        String vehicleId,
        String command,
        String targetSystem,
        String commandCategory,
        String result,
        String forkState,
        Boolean limitBottom,
        Boolean emergencyStopApplied,
        List<String> stoppedActions,
        Boolean requiresReset,
        String errorCode,
        String message,
        OffsetDateTime completedAt
) {
}
