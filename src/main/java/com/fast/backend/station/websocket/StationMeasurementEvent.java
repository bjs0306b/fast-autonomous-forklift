package com.fast.backend.station.websocket;

import com.fast.backend.station.dto.StationMeasurementResponse;

import java.time.LocalDateTime;

/**
 * 측정 스테이션 WebSocket 봉투(envelope, prompt16.md 9단계). 차량 이벤트({@code VehicleWebSocketEvent})와
 * 동일한 envelope 패턴을 신규 스테이션 도메인에 일관되게 적용한다 — AI cargo처럼 응답 객체를 그대로
 * 보내는 대신 eventType/stationId/occurredAt으로 감싸, 프론트가 이벤트 종류를 명시적으로 구분하게 한다.
 */
public record StationMeasurementEvent(
        StationMeasurementEventType eventType,
        String stationId,
        LocalDateTime occurredAt,
        StationMeasurementResponse data
) {

    public static StationMeasurementEvent completed(StationMeasurementResponse data, LocalDateTime occurredAt) {
        return new StationMeasurementEvent(
                StationMeasurementEventType.STATION_MEASUREMENT_COMPLETED, data.stationId(), occurredAt, data);
    }
}
