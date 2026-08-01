package com.fast.backend.vehicle.websocket;

/** 차량 이벤트용 STOMP 목적지 모음. */
public final class VehicleWebSocketTopics {

    public static final String STATUS_ALL = "/topic/vehicles/status";
    public static final String LOCATION_ALL = "/topic/vehicles/location";
    public static final String RESULT_ALL = "/topic/vehicles/result";
    public static final String PATH_ALL = "/topic/vehicles/path";

    private VehicleWebSocketTopics() {
    }

    public static String status(String vehicleId) { return STATUS_ALL + "/" + vehicleId; }
    public static String location(String vehicleId) { return LOCATION_ALL + "/" + vehicleId; }
    public static String result(String vehicleId) { return RESULT_ALL + "/" + vehicleId; }
    public static String path(String vehicleId) { return PATH_ALL + "/" + vehicleId; }
}
