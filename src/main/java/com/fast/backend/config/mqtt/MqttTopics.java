package com.fast.backend.config.mqtt;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MQTT 토픽 문자열을 중앙에서 관리한다. 토픽 패턴은 {@link MqttProperties.Topics}에서 가져오며,
 * 다른 클래스는 이 클래스를 통해서만 토픽을 생성/판별해야 한다(토픽 문자열 직접 작성 금지).
 */
@Component
public class MqttTopics {

    private final MqttProperties.Topics topics;
    private final Pattern statusTopicPattern;
    private final Pattern locationTopicPattern;
    private final Pattern pathTopicPattern;
    private final Pattern commandResultTopicPattern;
    private final Pattern forkStatusTopicPattern;
    private final Pattern errorTopicPattern;
    private final Pattern stationMeasurementTopicPattern;

    public MqttTopics(MqttProperties mqttProperties) {
        this.topics = mqttProperties.topics();
        this.statusTopicPattern = toSubscribePattern(topics.forkliftStatus());
        this.locationTopicPattern = toSubscribePattern(topics.forkliftLocation());
        this.pathTopicPattern = toSubscribePattern(topics.forkliftPath());
        this.commandResultTopicPattern = toSubscribePattern(topics.forkliftCommandResult());
        this.forkStatusTopicPattern = toSubscribePattern(topics.forkliftForkStatus());
        this.errorTopicPattern = toSubscribePattern(topics.forkliftError());
        this.stationMeasurementTopicPattern = toSubscribePattern(topics.stationMeasurement());
    }

    public String forkliftStatusSubscribeTopic() {
        return topics.forkliftStatus();
    }

    public String forkliftLocationSubscribeTopic() {
        return topics.forkliftLocation();
    }

    public String forkliftPathSubscribeTopic() {
        return topics.forkliftPath();
    }

    public String forkliftCommandResultSubscribeTopic() {
        return topics.forkliftCommandResult();
    }

    public String forkliftForkStatusSubscribeTopic() {
        return topics.forkliftForkStatus();
    }

    public String forkliftErrorSubscribeTopic() {
        return topics.forkliftError();
    }

    public String cargoDetectedTopic() {
        return topics.cargoDetected();
    }

    public String stationMeasurementSubscribeTopic() {
        return topics.stationMeasurement();
    }

    /**
     * 차량 명령 발행 토픽 {@code forklift/{vehicleId}/command}를 만든다.
     *
     * <p>이동(ROS2)·포크/적재(임베디드)·비상정지(ALL) <b>모든 명령이 이 토픽 하나</b>를 쓴다
     * (prompt32.md 1장 7번 확정). 수신 측은 payload의 {@code targetSystem}/{@code commandCategory}로
     * 자기 명령인지 판별한다.
     *
     * <p>구 {@code forkliftEmergency(...)}(= {@code forklift/{id}/emergency})는 제거됐다 —
     * 근거는 {@link MqttProperties.Topics} Javadoc 참고.
     */
    public String vehicleCommand(String vehicleId) {
        return String.format(topics.forkliftCommand(), requireForkliftId(vehicleId));
    }

    public boolean isForkliftStatusTopic(String topic) {
        return topic != null && statusTopicPattern.matcher(topic).matches();
    }

    public boolean isForkliftLocationTopic(String topic) {
        return topic != null && locationTopicPattern.matcher(topic).matches();
    }

    public boolean isForkliftPathTopic(String topic) {
        return topic != null && pathTopicPattern.matcher(topic).matches();
    }

    public boolean isForkliftCommandResultTopic(String topic) {
        return topic != null && commandResultTopicPattern.matcher(topic).matches();
    }

    public boolean isForkliftForkStatusTopic(String topic) {
        return topic != null && forkStatusTopicPattern.matcher(topic).matches();
    }

    public boolean isForkliftErrorTopic(String topic) {
        return topic != null && errorTopicPattern.matcher(topic).matches();
    }

    public boolean isCargoDetectedTopic(String topic) {
        return topics.cargoDetected().equals(topic);
    }

    public boolean isStationMeasurementTopic(String topic) {
        return topic != null && stationMeasurementTopicPattern.matcher(topic).matches();
    }

    /**
     * {@code fast/station/{station_id}/measurement}에서 station_id를 추출한다. isStationMeasurementTopic이
     * 이미 true로 확인된 토픽에서만 호출되므로 매칭은 항상 성공한다.
     */
    public String extractStationId(String topic) {
        if (topic == null) {
            throw new IllegalArgumentException("topic must not be null");
        }
        Matcher matcher = stationMeasurementTopicPattern.matcher(topic);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        throw new IllegalArgumentException("Cannot extract stationId from topic: " + topic);
    }

    public String extractForkliftId(String topic) {
        if (topic == null) {
            throw new IllegalArgumentException("topic must not be null");
        }
        Matcher statusMatcher = statusTopicPattern.matcher(topic);
        if (statusMatcher.matches()) {
            return statusMatcher.group(1);
        }
        Matcher locationMatcher = locationTopicPattern.matcher(topic);
        if (locationMatcher.matches()) {
            return locationMatcher.group(1);
        }
        Matcher pathMatcher = pathTopicPattern.matcher(topic);
        if (pathMatcher.matches()) {
            return pathMatcher.group(1);
        }
        Matcher commandResultMatcher = commandResultTopicPattern.matcher(topic);
        if (commandResultMatcher.matches()) {
            return commandResultMatcher.group(1);
        }
        Matcher forkStatusMatcher = forkStatusTopicPattern.matcher(topic);
        if (forkStatusMatcher.matches()) {
            return forkStatusMatcher.group(1);
        }
        Matcher errorMatcher = errorTopicPattern.matcher(topic);
        if (errorMatcher.matches()) {
            return errorMatcher.group(1);
        }
        throw new IllegalArgumentException("Cannot extract forkliftId from topic: " + topic);
    }

    private String requireForkliftId(String forkliftId) {
        if (forkliftId == null || forkliftId.isBlank()) {
            throw new IllegalArgumentException("forkliftId must not be null or blank");
        }
        return forkliftId;
    }

    /**
     * "forklift/+/status" 같은 MQTT 구독 와일드카드 패턴을,
     * 실제 수신 토픽("forklift/F01/status")과 정확히 매칭하기 위한 정규식으로 변환한다.
     * '+' 세그먼트만 캡처 그룹으로 바꾸고 나머지는 리터럴로 고정해, 단순 contains가 아닌
     * 세그먼트 단위의 정확한 구조 비교를 수행한다.
     */
    private static Pattern toSubscribePattern(String mqttWildcardTopic) {
        String[] segments = mqttWildcardTopic.split("/");
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                regex.append('/');
            }
            String segment = segments[i];
            if ("+".equals(segment)) {
                regex.append("([^/]+)");
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        regex.append('$');
        return Pattern.compile(regex.toString());
    }
}
