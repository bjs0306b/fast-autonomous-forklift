package com.fast.backend.isaac.dto;

import java.time.OffsetDateTime;

/**
 * {@code forklift/{id}/status} 토픽으로 수신되는 Isaac Sim 상태 메시지(prompt28.md 1장·4장·5장 합의 규격).
 *
 * <p>LWT(Last Will and Testament) OFFLINE 메시지는 이 레코드의 최소 형태로 온다 —
 * {@code battery}/{@code forkHeight}/{@code hasCargo}/{@code cargoId}/{@code footprint}/{@code timestamp}가
 * 전부 없거나 null일 수 있다. 그래서 이 필드들은 전부 nullable(박싱 타입)로 선언했다 — 필수/선택
 * 여부와 LWT 최소 검증 규칙은 {@code IsaacForkliftStatusService}가 담당한다(DTO 자체는 구조만 정의).
 *
 * <p>같은 토픽을 실물 ROS2용 {@code ForkliftStatusMessage}가 함께 쓴다 — {@code MqttMessageRouter}는
 * payload에 {@code forkHeight}/{@code hasCargo}/{@code footprint} 중 하나라도 있으면(일반 Isaac 상태),
 * 또는 {@code battery} 키 자체가 없으면(Isaac LWT) 이 DTO 경로로 판별한다. <b>이 판별 구조는
 * prompt32.md 1장 2번 확정에 따라 유지</b>한다.
 *
 * <p><b>확장 필드가 DB에 저장된다(prompt32.md 1장 4번)</b>: {@code forkHeight}/{@code hasCargo}/
 * {@code cargoId}/{@code footprint.length}/{@code footprint.width}는 이제 WebSocket 중계뿐 아니라
 * {@code vehicle_current_status}·{@code vehicle_status_history}에도 저장된다.
 *
 * <p>{@code timestamp}는 {@code +09:00} {@link OffsetDateTime}이다(prompt32.md 1장 6번).
 */
public record IsaacForkliftStatusMessage(
        String forkliftId,
        String status,
        Integer battery,
        Double forkHeight,
        Boolean hasCargo,
        String cargoId,
        Footprint footprint,
        OffsetDateTime timestamp
) {

    public record Footprint(Double length, Double width) {
    }
}
