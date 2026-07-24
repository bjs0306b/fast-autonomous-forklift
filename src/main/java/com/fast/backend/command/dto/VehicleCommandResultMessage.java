package com.fast.backend.command.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code forklift/{vehicleId}/command-result} 토픽으로 수신되는 <b>모든 명령 결과의 공통 구조</b>
 * (prompt32.md 1장 12번 확정 규격).
 *
 * <pre>
 * {
 *   "commandId": "CMD-003",
 *   "vehicleId": "REAL-F01",
 *   "targetSystem": "ALL",
 *   "commandCategory": "SAFETY",
 *   "command": "EMERGENCY_STOP",
 *   "result": "SUCCESS",
 *   "message": "주행과 포크 정지 완료",
 *   "completedAt": "2026-07-23T11:20:28+09:00"
 * }
 * </pre>
 *
 * <p><b>하위 호환(prompt32.md 3장 5번)</b><br>
 * 외부 임베디드 담당자가 아직 이전 JSON을 보낼 수 있어 <b>읽기 호환</b>을 제공한다:
 * <ul>
 *   <li>{@code forkliftId} → {@code vehicleId}의 {@link JsonAlias}로 수신 가능</li>
 *   <li>{@code targetSystem}/{@code commandCategory}는 <b>nullable</b>이다. 값이 없으면 구 형식으로
 *       판단해 해당 항목 검증만 건너뛰고 경고 로그를 남긴다({@code VehicleCommandResultService} 참고).
 *       값이 있으면 발행했던 명령과 일치하는지 반드시 검증한다.</li>
 *   <li>구 임베디드 결과 전용 필드({@code forkState}, {@code limitBottom}, {@code emergencyStopApplied},
 *       {@code stoppedActions}, {@code requiresReset}, {@code errorCode})는 확정 규격 예시에 없지만
 *       비상정지 결과의 핵심 정보라 <b>선택 필드로 유지</b>한다 — 규격에서 빠졌다는 이유로 이미 수신 중인
 *       안전 정보를 버리지 않는다.</li>
 * </ul>
 *
 * @deprecated 되는 항목은 없다. 다만 {@code forkliftId} alias와 null {@code targetSystem} 허용은
 *             <b>과도기 조치</b>이며, 임베디드·ROS2 양쪽이 새 규격으로 전환한 것이 확인되면 제거한다.
 */
public record VehicleCommandResultMessage(
        String commandId,
        @JsonAlias("forkliftId") String vehicleId,
        String targetSystem,
        String commandCategory,
        String command,
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

    /** 구 형식(targetSystem/commandCategory 없음)으로 수신됐는지. */
    public boolean isLegacyFormat() {
        return targetSystem == null || commandCategory == null;
    }
}
