package com.fast.backend.embedded.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code forklift/{id}/command-result} 토픽으로 수신되는 실물 지게차 명령 수행 결과(prompt29.md 7장).
 * 일반 결과와 비상정지(EMERGENCY_STOP/RESET_ESTOP) 결과를 하나의 레코드로 표현한다 — 7장의 "키 정의"
 * 표 자체가 이미 두 경우를 하나로 통합해서 제시하고 있고, 필드 수(12개)가 의미를 흐릴 만큼 많지 않다고
 * 판단했다(10장 "선택 필드가 너무 많아 의미 불명확해지면 분리 가능" 조건에는 해당하지 않음). 공통 필드
 * (commandId/forkliftId/command/result/completedAt)만 필수이고 나머지는 상황에 따라 null이다.
 */
public record EmbeddedCommandResultMessage(
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
