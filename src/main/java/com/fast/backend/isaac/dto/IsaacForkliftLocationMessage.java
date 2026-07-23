package com.fast.backend.isaac.dto;

import java.time.LocalDateTime;

/**
 * {@code forklift/{id}/location} 토픽으로 수신되는 Isaac Sim 위치 메시지(prompt28.md 1장·3장 합의 규격).
 *
 * <p>같은 토픽 이름을 실물 ROS2 위치 메시지({@code ForkliftLocationMessage}, "vehicleId" 키·중첩
 * {@code position} 구조)가 이미 사용하고 있어 필드 구조가 다르다(answer28.md 3장·4장에서 충돌을
 * 분석·문서화했다). {@code MqttMessageRouter}는 payload에 "vehicleId" 키가 있으면 기존 ROS2 경로로,
 * "forkliftId" 키가 있으면 이 DTO(Isaac 경로)로 구분해서 역직렬화한다 — 두 스펙 모두 서로 다른 필수
 * 식별자 키를 쓰기 때문에 이 판별 방식이 안전하다.
 *
 * <p>단위는 합의 규격을 그대로 따른다: {@code x}/{@code y}는 m, {@code direction}은 rad(-π~π,
 * 0=+X, CCW 양수), {@code speed}는 m/s. degree로 임의 변환하지 않는다.
 */
public record IsaacForkliftLocationMessage(
        String forkliftId,
        Double x,
        Double y,
        Double direction,
        Double speed,
        LocalDateTime timestamp
) {
}
