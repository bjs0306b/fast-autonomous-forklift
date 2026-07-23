package com.fast.backend.vehicle.websocket;

/**
 * WebSocket으로 전송하는 이벤트 envelope의 {@code eventType} 값(prompt20.md 6장).
 */
public enum VehicleWebSocketEventType {
    VEHICLE_STATUS_UPDATED,
    VEHICLE_LOCATION_UPDATED,
    VEHICLE_COMMAND_RESULT_UPDATED,
    /** Isaac Sim 경로 메시지 전용(prompt28.md 12장). */
    VEHICLE_PATH_UPDATED,
    /** 실물 포크 상태 전용(prompt29.md 19장). */
    VEHICLE_FORK_STATUS_UPDATED,
    /** 실물 임베디드 오류 이벤트 전용(prompt29.md 19장). */
    VEHICLE_ERROR_OCCURRED
}
