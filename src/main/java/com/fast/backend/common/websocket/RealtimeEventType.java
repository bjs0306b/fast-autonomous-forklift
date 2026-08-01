package com.fast.backend.common.websocket;

/** 백엔드가 현재 발행하는 실시간 이벤트 유형. */
public enum RealtimeEventType {
    VEHICLE_STATUS_UPDATED,
    VEHICLE_LOCATION_UPDATED,
    VEHICLE_PATH_UPDATED,
    VEHICLE_COMMAND_RESULT_UPDATED,
    STATION_MEASUREMENT_COMPLETED
}
