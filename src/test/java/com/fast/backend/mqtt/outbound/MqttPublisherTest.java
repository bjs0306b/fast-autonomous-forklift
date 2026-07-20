package com.fast.backend.mqtt.outbound;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.mqtt.gateway.MqttGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MqttGateway를 모킹해 실제 MQTT Broker 없이 직렬화/헤더 전달 로직만 검증한다.
 */
class MqttPublisherTest {

    private MqttGateway mqttGateway;
    private MqttPublisher mqttPublisher;

    @BeforeEach
    void setUp() {
        mqttGateway = mock(MqttGateway.class);
        mqttPublisher = new MqttPublisher(mqttGateway, new ObjectMapper());
    }

    record TestPayload(String message) {
    }

    @Test
    void publish_serializesObjectAndCallsGatewayWithCorrectArguments() {
        mqttPublisher.publish(new TestPayload("hello"), "test/fast", 1, false);

        verify(mqttGateway, times(1)).publish("{\"message\":\"hello\"}", "test/fast", 1, false);
    }

    @Test
    void publishRaw_passesPayloadThroughWithoutSerialization() {
        mqttPublisher.publishRaw("hello mqtt", "test/fast", 1, false);

        verify(mqttGateway, times(1)).publish("hello mqtt", "test/fast", 1, false);
    }

    @Test
    void publish_serializationFailure_throwsMqttPublishException() throws JsonProcessingException {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new TestJsonProcessingException("boom"));
        MqttPublisher publisherWithFailingMapper = new MqttPublisher(mqttGateway, failingMapper);

        assertThatThrownBy(() -> publisherWithFailingMapper.publish(new TestPayload("x"), "topic", 1, false))
                .isInstanceOf(MqttPublishException.class);
    }

    private static final class TestJsonProcessingException extends JsonProcessingException {
        private TestJsonProcessingException(String message) {
            super(message);
        }
    }
}
