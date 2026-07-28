package com.fast.backend.command.dto;

import jakarta.validation.constraints.Size;

/**
 * 안전 명령(STOP/EMERGENCY_STOP) 요청 바디(prompt53.md 5장). body가 없어도(null) 허용되도록 두 필드 모두
 * 선택값이다 — 컨트롤러에서 {@code required=false}로 받아 null이면 빈 요청으로 처리한다.
 *
 * <p>{@code reason}은 재사용하는 {@code embedded_vehicle_command.reason}(VARCHAR 100)에 저장되므로 100자로
 * 제한한다. {@code requestedBy}는 감사용 표기이며, 이번 재사용 설계에서는 DB 컬럼이 없어 <b>영속되지 않고</b>
 * WebSocket 이벤트/로그로만 노출된다(최종 보고서 제한사항 참고).
 */
public record SafetyCommandRequest(
        @Size(max = 100, message = "reason은 100자 이하여야 합니다.") String reason,
        @Size(max = 100, message = "requestedBy는 100자 이하여야 합니다.") String requestedBy
) {
    public String reasonOrNull() {
        return (reason == null || reason.isBlank()) ? null : reason;
    }
}
