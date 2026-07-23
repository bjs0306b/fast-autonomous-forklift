package com.fast.backend.isaac.dto;

import java.time.LocalDateTime;

/**
 * {@code forklift/{id}/status} 토픽으로 수신되는 Isaac Sim 상태 메시지(prompt28.md 1장·4장·5장 합의 규격).
 *
 * <p>LWT(Last Will and Testament) OFFLINE 메시지는 이 레코드의 최소 형태로 온다 —
 * {@code battery}/{@code forkHeight}/{@code hasCargo}/{@code cargoId}/{@code footprint}/{@code timestamp}가
 * 전부 없거나 null일 수 있다(5장). 그래서 이 필드들은 전부 nullable(박싱 타입)로 선언했다 — 필수/선택
 * 여부와 LWT 최소 검증 규칙은 {@code IsaacForkliftStatusService}가 담당한다(DTO 자체는 구조만 정의).
 *
 * <p>같은 토픽을 실물 ROS2용 {@code ForkliftStatusMessage}("forkliftId"/"status"/"battery"/"timestamp"
 * 4개 필드만 있는 구 Mock 규격)가 이미 쓰고 있다 — {@code MqttMessageRouter}는 payload에
 * "forkHeight"/"hasCargo"/"footprint" 중 하나라도 있으면(일반 Isaac 상태), 또는 "battery" 키 자체가
 * 없으면(Isaac LWT) 이 DTO 경로로, 그 외에는 기존 ROS2 경로로 판별한다(answer28.md 3장 근거).
 */
public record IsaacForkliftStatusMessage(
        String forkliftId,
        String status,
        Integer battery,
        Double forkHeight,
        Boolean hasCargo,
        String cargoId,
        Footprint footprint,
        LocalDateTime timestamp
) {

    public record Footprint(Double length, Double width) {
    }
}
