package com.fast.backend.embedded.service;

import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.embedded.dto.EmbeddedForkliftCommandMessage;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code forklift/{id}/command} 실물 명령 발행(prompt29.md 3.2장·14장)의 토픽·QoS·retained를 검증한다.
 * 실제 MQTT 브로커 없이 {@link MqttPublisher}를 모킹해 검증한다.
 */
class EmbeddedForkliftCommandPublisherTest {

    private MqttPublisher mqttPublisher;
    private MqttTopics mqttTopics;
    private MqttProperties mqttProperties;
    private EmbeddedForkliftCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        mqttPublisher = mock(MqttPublisher.class);
        mqttTopics = mock(MqttTopics.class);
        mqttProperties = mock(MqttProperties.class);
        when(mqttTopics.forkliftCommand("REAL01")).thenReturn("forklift/REAL01/command");
        when(mqttProperties.defaultQos()).thenReturn(1);
        publisher = new EmbeddedForkliftCommandPublisher(mqttPublisher, mqttTopics, mqttProperties);
    }

    @Test
    void publish_publishesToAgreedTopicWithDefaultQosAndNotRetained() {
        EmbeddedForkliftCommandMessage message = new EmbeddedForkliftCommandMessage(
                "CMD-001", "REAL01", "FORK_UP", "화물 상차", LocalDateTime.now());

        publisher.publish(message);

        verify(mqttPublisher).publish(eq(message), eq("forklift/REAL01/command"), eq(1), eq(false));
    }
}
