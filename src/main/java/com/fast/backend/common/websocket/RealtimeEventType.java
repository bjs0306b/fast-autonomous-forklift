package com.fast.backend.common.websocket;

/**
 * 모든 WebSocket 실시간 이벤트의 {@code eventType} 값(prompt32.md 1장 13번 확정 규격).
 *
 * <p>이전에는 차량({@code VehicleWebSocketEventType})과 스테이션({@code StationMeasurementEventType})이
 * 각각 별도 enum을 갖고 AI는 eventType 자체가 없었다. 프론트가 도메인별로 다른 구독 규격을 다뤄야 하는
 * 문제를 없애기 위해 하나의 enum으로 통합했다.
 */
public enum RealtimeEventType {

    VEHICLE_STATUS_UPDATED,
    VEHICLE_LOCATION_UPDATED,
    VEHICLE_PATH_UPDATED,
    VEHICLE_COMMAND_RESULT_UPDATED,
    VEHICLE_FORK_STATUS_UPDATED,
    VEHICLE_ERROR_OCCURRED,
    AI_CARGO_ANALYSIS_COMPLETED,
    STATION_MEASUREMENT_COMPLETED
}
