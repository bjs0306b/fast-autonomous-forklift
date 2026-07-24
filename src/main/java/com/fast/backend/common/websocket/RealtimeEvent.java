package com.fast.backend.common.websocket;

import java.time.OffsetDateTime;

/**
 * 차량·AI·스테이션 이벤트가 <b>모두 공유하는</b> WebSocket 공통 envelope(prompt32.md 1장 13번 확정 규격).
 *
 * <pre>
 * {
 *   "eventType": "VEHICLE_STATUS_UPDATED",
 *   "vehicleId": "REAL-F01",
 *   "occurredAt": "2026-07-23T11:20:27+09:00",
 *   "data": { ... }
 * }
 * </pre>
 *
 * <p><b>필드 규칙</b>
 * <ul>
 *   <li>{@code eventType}: 필수.</li>
 *   <li>{@code vehicleId}: <b>nullable</b>. 차량 이벤트는 항상 채워지고, 차량과 연결되지 않은 AI 분석
 *       결과나 차량이 배정되지 않은 스테이션 측정 이벤트는 {@code null}이다.</li>
 *   <li>{@code occurredAt}: 필수. 서버 처리 시각이 아니라 <b>이벤트가 실제로 발생한 시각</b>
 *       (상태 갱신이면 {@code messageAt})을 {@code +09:00} 오프셋으로 담는다
 *       ({@link com.fast.backend.common.time.CommunicationTime}).</li>
 *   <li>{@code data}: 필수. 도메인별 payload.</li>
 * </ul>
 *
 * <p><b>도메인 식별자 위치</b>: 모든 이벤트에서 최상위 식별자 필드 이름은 {@code vehicleId} 하나로
 * 통일한다. AI의 {@code cargoId}, 스테이션의 {@code stationId}/{@code measurementId} 같은 도메인 고유
 * 식별자는 최상위로 올리지 않고 {@code data} 내부에 그대로 유지한다.
 *
 * <p>destination 자체는 통일하지 않는다 — 목표는 payload 구조의 통일이지 경로 통합이 아니다
 * (기존 {@code /topic/vehicles/*}, {@code /topic/ai/cargo-analysis}, {@code /topic/stations/*} 유지).
 */
public record RealtimeEvent<T>(
        RealtimeEventType eventType,
        String vehicleId,
        OffsetDateTime occurredAt,
        T data
) {

    public static <T> RealtimeEvent<T> of(
            RealtimeEventType eventType, String vehicleId, OffsetDateTime occurredAt, T data) {
        return new RealtimeEvent<>(eventType, vehicleId, occurredAt, data);
    }
}
