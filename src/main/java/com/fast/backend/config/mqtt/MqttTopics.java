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

    public MqttTopics(MqttProperties mqttProperties) {
        this.topics = mqttProperties.topics();
        this.statusTopicPattern = toSubscribePattern(topics.forkliftStatus());
        this.locationTopicPattern = toSubscribePattern(topics.forkliftLocation());
    }

    public String forkliftStatusSubscribeTopic() {
        return topics.forkliftStatus();
    }

    public String forkliftLocationSubscribeTopic() {
        return topics.forkliftLocation();
    }

    public String cargoDetectedTopic() {
        return topics.cargoDetected();
    }

    public String forkliftCommand(String forkliftId) {
        return String.format(topics.forkliftCommand(), requireForkliftId(forkliftId));
    }

    public String forkliftEmergency(String forkliftId) {
        return String.format(topics.forkliftEmergency(), requireForkliftId(forkliftId));
    }

    public boolean isForkliftStatusTopic(String topic) {
        return topic != null && statusTopicPattern.matcher(topic).matches();
    }

    public boolean isForkliftLocationTopic(String topic) {
        return topic != null && locationTopicPattern.matcher(topic).matches();
    }

    public boolean isCargoDetectedTopic(String topic) {
        return topics.cargoDetected().equals(topic);
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
