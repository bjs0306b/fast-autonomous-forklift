package com.fast.backend.station.websocket;

/**
 * 측정 스테이션 WebSocket 이벤트 종류(prompt16.md 9단계). 현재는 측정 완료 이벤트 하나뿐이다 —
 * 규격에 있는 최소 신규 enum만 둔다(원칙 8번).
 */
public enum StationMeasurementEventType {
    STATION_MEASUREMENT_COMPLETED
}
