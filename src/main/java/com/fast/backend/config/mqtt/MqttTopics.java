package com.fast.backend.config.mqtt;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 백엔드 MQTT 경계에서 사용하는 토픽을 한 곳에서 관리한다. */
@Component
public class MqttTopics {

    private static final String ISAAC_VEHICLE_TASK = "fast/v1/vehicle/%s/task";
    private static final String ISAAC_VEHICLE_CONTROL = "fast/v1/vehicle/%s/control";
    private static final String ISAAC_VEHICLE_CARGO = "fast/v1/vehicle/%s/cargo";
    private static final String ISAAC_GLOBAL_CONTROL = "fast/v1/control/all";

    private final MqttProperties.Topics topics;
    private final Pattern statusPattern;
    private final Pattern locationPattern;
    private final Pattern pathPattern;
    private final Pattern commandResultPattern;
    private final Pattern isaacTelemetryPattern;
    private final Pattern isaacEventPattern;
    private final Pattern forkliftArrivedPattern;

    public MqttTopics(MqttProperties properties) {
        this.topics = properties.topics();
        this.statusPattern = toPattern(topics.forkliftStatus());
        this.locationPattern = toPattern(topics.forkliftLocation());
        this.pathPattern = toPattern(topics.forkliftPath());
        this.commandResultPattern = toPattern(topics.forkliftCommandResult());
        this.isaacTelemetryPattern = toPattern(topics.isaacTelemetry());
        this.isaacEventPattern = toPattern(topics.isaacEvent());
        this.forkliftArrivedPattern = toPattern(topics.forkliftArrived());
    }

    public String forkliftStatusSubscribeTopic() { return topics.forkliftStatus(); }
    public String forkliftLocationSubscribeTopic() { return topics.forkliftLocation(); }
    public String forkliftPathSubscribeTopic() { return topics.forkliftPath(); }
    public String forkliftCommandResultSubscribeTopic() { return topics.forkliftCommandResult(); }
    public String stationMeasureRequest() { return topics.stationMeasureRequest(); }
    public String isaacTelemetrySubscribeTopic() { return topics.isaacTelemetry(); }
    public String isaacEventSubscribeTopic() { return topics.isaacEvent(); }
    public String forkliftArrivedSubscribeTopic() { return topics.forkliftArrived(); }

    public String vehicleCommand(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("vehicleId must not be blank");
        }
        return String.format(topics.forkliftCommand(), vehicleId);
    }

    /** 현재 Isaac Sim 제어 계약의 차량별 토픽. */
    public String isaacVehicleControl(String externalVehicleId) {
        if (externalVehicleId == null || externalVehicleId.isBlank()) {
            throw new IllegalArgumentException("externalVehicleId must not be blank");
        }
        return String.format(ISAAC_VEHICLE_CONTROL, externalVehicleId);
    }

    /** 현재 Isaac/ROS2 이동 작업 계약의 차량별 토픽. */
    public String isaacVehicleTask(String externalVehicleId) {
        if (externalVehicleId == null || externalVehicleId.isBlank()) {
            throw new IllegalArgumentException("externalVehicleId must not be blank");
        }
        return String.format(ISAAC_VEHICLE_TASK, externalVehicleId);
    }

    /** 현재 Isaac/ROS2 화물 제어 계약의 차량별 토픽. */
    public String isaacVehicleCargo(String externalVehicleId) {
        if (externalVehicleId == null || externalVehicleId.isBlank()) {
            throw new IllegalArgumentException("externalVehicleId must not be blank");
        }
        return String.format(ISAAC_VEHICLE_CARGO, externalVehicleId);
    }

    /** 현재 Isaac Sim 제어 계약의 전체 차량 토픽. */
    public String isaacGlobalControl() {
        return ISAAC_GLOBAL_CONTROL;
    }

    public boolean isForkliftStatusTopic(String topic) { return matches(statusPattern, topic); }
    public boolean isForkliftLocationTopic(String topic) { return matches(locationPattern, topic); }
    public boolean isForkliftPathTopic(String topic) { return matches(pathPattern, topic); }
    public boolean isForkliftCommandResultTopic(String topic) { return matches(commandResultPattern, topic); }
    public boolean isIsaacTelemetryTopic(String topic) { return matches(isaacTelemetryPattern, topic); }
    public boolean isIsaacEventTopic(String topic) { return matches(isaacEventPattern, topic); }
    public boolean isForkliftArrivedTopic(String topic) { return matches(forkliftArrivedPattern, topic); }

    /**
     * Isaac telemetry 토픽에서 차량 ID 를 뽑는다.
     *
     * <p>{@link #extractForkliftId(String)} 와 분리한 이유: 그쪽은 {@code forklift/…} 계열 4개 패턴만
     * 훑는다. 한 메서드에 성격이 다른 계약을 섞으면 "어느 패턴에 걸렸는지" 가 호출부에서 보이지 않는다.
     *
     * <p>여기서 나온 값은 <b>Isaac 원본 ID</b>({@code sim01})다. DB 기준 ID 로 바꾸는 일은
     * {@code VehicleIdAliasResolver} 가 한다.
     */
    public String extractIsaacVehicleId(String topic) {
        for (Pattern pattern : new Pattern[] {isaacTelemetryPattern, isaacEventPattern}) {
            Matcher matcher = pattern.matcher(topic == null ? "" : topic);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        throw new IllegalArgumentException("Cannot extract vehicleId from Isaac telemetry topic: " + topic);
    }

    public String extractForkliftId(String topic) {
        for (Pattern pattern : new Pattern[] {
                statusPattern, locationPattern, pathPattern, commandResultPattern, forkliftArrivedPattern}) {
            Matcher matcher = pattern.matcher(topic == null ? "" : topic);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        throw new IllegalArgumentException("Cannot extract vehicleId from topic: " + topic);
    }

    private static boolean matches(Pattern pattern, String topic) {
        return topic != null && pattern.matcher(topic).matches();
    }

    private static Pattern toPattern(String mqttTopic) {
        StringBuilder regex = new StringBuilder("^");
        String[] segments = mqttTopic.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) regex.append('/');
            regex.append("+".equals(segments[i]) ? "([^/]+)" : Pattern.quote(segments[i]));
        }
        return Pattern.compile(regex.append('$').toString());
    }
}
