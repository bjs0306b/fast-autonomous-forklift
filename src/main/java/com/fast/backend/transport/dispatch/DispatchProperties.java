package com.fast.backend.transport.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운반 디스패치 설정(prompt48.md 19장). {@code dispatch.mqtt.enabled}로 실제 MQTT 발행을 안전하게
 * 켜고 끈다. <b>기본값 false</b> — 운영 환경에서 실수로 차량 명령이 발행되지 않도록 한다.
 *
 * <ul>
 *   <li>false: 실제 publish를 하지 않고 명확한 비활성 오류로 처리(테스트·기본).</li>
 *   <li>true: 기존 {@code MqttPublisher}를 통해 실제 발행.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "dispatch")
public record DispatchProperties(
        Mqtt mqtt
) {
    public record Mqtt(boolean enabled) {
    }

    public boolean mqttEnabled() {
        return mqtt != null && mqtt.enabled();
    }
}
