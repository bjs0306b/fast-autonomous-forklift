package com.fast.backend.embedded.service;

import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.embedded.dto.EmbeddedForkliftCommandMessage;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import org.springframework.stereotype.Component;

/**
 * 백엔드가 {@code forklift/{id}/command} 토픽으로 실물 지게차(REAL01) 명령을 발행한다(prompt29.md
 * 3.2장·14장). 기존 {@link com.fast.backend.isaac.service.IsaacForkliftCommandPublisher}를 복제하지
 * 않고, 같은 {@link MqttPublisher}·{@link MqttTopics#forkliftCommand(String)}를 재사용하는 별도
 * Publisher만 추가했다(작업 원칙 4·5·6번) — SIM 명령 JSON은 이 클래스 존재와 무관하게 그대로 유지된다.
 *
 * <p>QoS/retained는 합의 문서에 명시되지 않았다(19장·4장 "미확정 사항") — Isaac Publisher와 동일하게
 * {@code mqtt.default-qos}를 임시로 재사용하고 {@code retained=false}를 쓴다(명령이 재접속 시 오래된
 * 값으로 재전달되면 위험하다는 동일한 근거).
 */
@Component
public class EmbeddedForkliftCommandPublisher {

    private static final boolean RETAINED = false;

    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;
    private final MqttProperties mqttProperties;

    public EmbeddedForkliftCommandPublisher(MqttPublisher mqttPublisher, MqttTopics mqttTopics, MqttProperties mqttProperties) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
        this.mqttProperties = mqttProperties;
    }

    public void publish(EmbeddedForkliftCommandMessage message) {
        String topic = mqttTopics.forkliftCommand(message.forkliftId());
        mqttPublisher.publish(message, topic, mqttProperties.defaultQos(), RETAINED);
    }
}
