package com.fast.backend.vehicle.websocket;

/**
 * WebSocket STOMP 토픽 문자열을 한 곳에서 관리한다(prompt20.md 10장 "토픽 문자열을 여러 클래스에
 * 하드코딩하지 말 것"). {@link VehicleWebSocketBroadcaster}만 이 클래스를 사용한다.
 */
public final class VehicleWebSocketTopics {

    public static final String STATUS_ALL = "/topic/vehicles/status";
    public static final String LOCATION_ALL = "/topic/vehicles/location";
    public static final String RESULT_ALL = "/topic/vehicles/result";
    /** Isaac Sim 경로 메시지 전용(prompt28.md 6장 권장 destination). */
    public static final String PATH_ALL = "/topic/vehicles/path";
    /** 실물 포크 상태 전용(prompt29.md 19장 권장 destination). */
    public static final String FORK_STATUS_ALL = "/topic/vehicles/fork-status";
    /** 실물 임베디드 오류 전용(prompt29.md 19장 권장 destination). */
    public static final String ERRORS_ALL = "/topic/vehicles/errors";

    private VehicleWebSocketTopics() {
    }

    public static String status(String vehicleId) {
        return STATUS_ALL + "/" + vehicleId;
    }

    public static String location(String vehicleId) {
        return LOCATION_ALL + "/" + vehicleId;
    }

    public static String result(String vehicleId) {
        return RESULT_ALL + "/" + vehicleId;
    }

    public static String path(String vehicleId) {
        return PATH_ALL + "/" + vehicleId;
    }

    public static String forkStatus(String vehicleId) {
        return FORK_STATUS_ALL + "/" + vehicleId;
    }

    public static String errors(String vehicleId) {
        return ERRORS_ALL + "/" + vehicleId;
    }
}
