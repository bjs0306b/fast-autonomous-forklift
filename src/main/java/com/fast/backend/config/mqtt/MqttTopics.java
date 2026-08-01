package com.fast.backend.config.mqtt;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 백엔드 MQTT 경계에서 사용하는 토픽을 한 곳에서 관리한다. */
@Component
public class MqttTopics {

    private final MqttProperties.Topics topics;
    private final Pattern statusPattern;
    private final Pattern locationPattern;
    private final Pattern pathPattern;
    private final Pattern commandResultPattern;

    public MqttTopics(MqttProperties properties) {
        this.topics = properties.topics();
        this.statusPattern = toPattern(topics.forkliftStatus());
        this.locationPattern = toPattern(topics.forkliftLocation());
        this.pathPattern = toPattern(topics.forkliftPath());
        this.commandResultPattern = toPattern(topics.forkliftCommandResult());
    }

    public String forkliftStatusSubscribeTopic() { return topics.forkliftStatus(); }
    public String forkliftLocationSubscribeTopic() { return topics.forkliftLocation(); }
    public String forkliftPathSubscribeTopic() { return topics.forkliftPath(); }
    public String forkliftCommandResultSubscribeTopic() { return topics.forkliftCommandResult(); }
    public String stationMeasurementSubscribeTopic() { return topics.stationMeasurement(); }

    public String vehicleCommand(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("vehicleId must not be blank");
        }
        return String.format(topics.forkliftCommand(), vehicleId);
    }

    public boolean isForkliftStatusTopic(String topic) { return matches(statusPattern, topic); }
    public boolean isForkliftLocationTopic(String topic) { return matches(locationPattern, topic); }
    public boolean isForkliftPathTopic(String topic) { return matches(pathPattern, topic); }
    public boolean isForkliftCommandResultTopic(String topic) { return matches(commandResultPattern, topic); }
    public boolean isStationMeasurementTopic(String topic) {
        return topic != null && topic.equals(topics.stationMeasurement());
    }

    public String extractForkliftId(String topic) {
        for (Pattern pattern : new Pattern[] {statusPattern, locationPattern, pathPattern, commandResultPattern}) {
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
