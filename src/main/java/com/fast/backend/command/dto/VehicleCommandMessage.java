package com.fast.backend.command.dto;

import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;

import java.time.OffsetDateTime;

/**
 * 백엔드가 {@code forklift/{vehicleId}/command} 토픽으로 발행하는 <b>모든 명령의 공통 envelope</b>
 * (prompt32.md 1장 7·8번 확정 규격).
 *
 * <pre>
 * {
 *   "commandId": "CMD-001",
 *   "vehicleId": "REAL-F01",
 *   "targetSystem": "ROS2",
 *   "commandCategory": "MOVE",
 *   "command": "MOVE",
 *   "payload": { "destination": { "x": 5.0, "y": 6.0, "heading": 180.0, "frameId": "map" } },
 *   "reason": null,
 *   "timestamp": "2026-07-23T11:20:27+09:00"
 * }
 * </pre>
 *
 * <p><b>이 레코드 하나가 이전의 두 DTO를 대체한다</b> — 구
 * {@code IsaacForkliftCommandMessage}(SIM 이동 명령: forkliftId+destination)와 구
 * {@code EmbeddedForkliftCommandMessage}(실물 명령: commandId+reason)가 같은 토픽을 서로 다른 스키마로
 * 공유하던 문제(구 communication-protocol.md §4.2의 `미확정` 항목)가 해소됐다. 수신 측은
 * {@code targetSystem}과 {@code commandCategory}만 보고 자기 명령인지 즉시 판별할 수 있다.
 *
 * <p>필수 필드: {@code commandId}, {@code vehicleId}, {@code targetSystem}, {@code commandCategory},
 * {@code command}, {@code timestamp}. 선택 필드: {@code payload}, {@code reason}.
 *
 * <p>식별자 필드 이름은 {@code forkliftId}가 아니라 <b>{@code vehicleId}</b>다(1장 2번 확정 —
 * 새로 정의하는 명령 메시지와 WebSocket envelope는 Java JSON 표기 관례를 따른다). 기존 ROS2/Isaac
 * <b>상태·위치</b> 메시지의 식별자 키는 확정 규격대로 바꾸지 않고 그대로 유지된다.
 *
 * <p>{@code timestamp}는 백엔드가 채운다 — REST 요청자가 직접 넣지 않는다(3장 2번).
 */
public record VehicleCommandMessage(
        String commandId,
        String vehicleId,
        VehicleCommandTargetSystem targetSystem,
        VehicleCommandCategory commandCategory,
        VehicleCommandType command,
        VehicleCommandPayload payload,
        String reason,
        OffsetDateTime timestamp
) {
}
