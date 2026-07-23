package com.fast.backend.isaac.service;

import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.isaac.dto.IsaacForkliftCommandMessage;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code forklift/{id}/command} 발행(prompt28.md 7장·15장)의 토픽·payload·QoS·retained를 검증한다.
 * 실제 MQTT 브로커 없이 {@link MqttPublisher}를 모킹해 검증한다.
 */
class IsaacForkliftCommandPublisherTest {

    private MqttPublisher mqttPublisher;
    private MqttTopics mqttTopics;
    private MqttProperties mqttProperties;
    private IsaacForkliftCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        mqttPublisher = mock(MqttPublisher.class);
        mqttTopics = mock(MqttTopics.class);
        mqttProperties = mock(MqttProperties.class);
        when(mqttTopics.forkliftCommand("SIM01")).thenReturn("forklift/SIM01/command");
        when(mqttProperties.defaultQos()).thenReturn(1);
        publisher = new IsaacForkliftCommandPublisher(mqttPublisher, mqttTopics, mqttProperties);
    }

    @Test
    void publishMove_publishesToAgreedTopicWithDestinationAndQos() {
        publisher.publishMove("SIM01", 2.40, 3.10, 0.0);

        ArgumentCaptor<IsaacForkliftCommandMessage> payloadCaptor =
                ArgumentCaptor.forClass(IsaacForkliftCommandMessage.class);
        verify(mqttPublisher).publish(payloadCaptor.capture(), eq("forklift/SIM01/command"), eq(1), eq(false));
        IsaacForkliftCommandMessage message = payloadCaptor.getValue();
        assertThat(message.forkliftId()).isEqualTo("SIM01");
        assertThat(message.command()).isEqualTo("MOVE");
        assertThat(message.destination().x()).isEqualTo(2.40);
        assertThat(message.destination().y()).isEqualTo(3.10);
        assertThat(message.destination().direction()).isEqualTo(0.0);
        assertThat(message.timestamp()).isNotNull();
    }

    @Test
    void publishMove_nonFiniteDestination_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> publisher.publishMove("SIM01", Double.NaN, 3.10, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publisher.publishMove("SIM01", 2.40, Double.POSITIVE_INFINITY, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishCommand_withoutDestination_publishesNullDestination() {
        publisher.publishCommand("SIM01", "STOP");

        ArgumentCaptor<IsaacForkliftCommandMessage> payloadCaptor =
                ArgumentCaptor.forClass(IsaacForkliftCommandMessage.class);
        verify(mqttPublisher).publish(payloadCaptor.capture(), any(), anyInt(), anyBoolean());
        assertThat(payloadCaptor.getValue().command()).isEqualTo("STOP");
        assertThat(payloadCaptor.getValue().destination()).isNull();
    }

    @Test
    void publishCommand_blankForkliftId_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> publisher.publishCommand(" ", "STOP"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
