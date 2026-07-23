package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;

/**
 * 모든 차량 WebSocket 메시지가 공유하는 공통 envelope(prompt20.md 6장).
 *
 * <p>{@code occurredAt}은 서버가 메시지를 처리한 시각({@code receivedAt})이 아니라, 이벤트가 실제로
 * 발생한 시각(상태 갱신이면 {@code messageAt})을 담는다 — 6장 예시에서 {@code occurredAt}과
 * {@code data.messageAt}이 같고 {@code data.receivedAt}만 더 늦은 것과 동일한 의도다.
 *
 * <p>시간 타입은 이 프로젝트의 기존 기준(내부/브로드캐스트 값은 {@link LocalDateTime}, REST 요청 경계에서만
 * {@code OffsetDateTime} 사용)을 그대로 따른다 — prompt20.md 18장 "LocalDateTime과 OffsetDateTime을
 * 임의로 혼용하지 말 것" 조건에 따라 6장 예시의 {@code OffsetDateTime} 표기 대신 이 타입을 사용한다.
 */
public record VehicleWebSocketEvent<T>(
        VehicleWebSocketEventType eventType,
        String vehicleId,
        LocalDateTime occurredAt,
        T data
) {

    public static <T> VehicleWebSocketEvent<T> of(
            VehicleWebSocketEventType eventType, String vehicleId, LocalDateTime occurredAt, T data) {
        return new VehicleWebSocketEvent<>(eventType, vehicleId, occurredAt, data);
    }
}
