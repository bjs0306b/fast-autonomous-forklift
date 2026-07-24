package com.fast.backend.mqtt.inbound;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MqttMessageReceiverTest {

    private MqttMessageRouter router;
    private MqttMessageReceiver receiver;

    @BeforeEach
    void setUp() {
        router = mock(MqttMessageRouter.class);
        receiver = new MqttMessageReceiver(router);
    }

    @Test
    void receive_forwardsTopicAndPayloadToRouter() {
        Message<String> message = mqttMessage(
                "forklift/REAL-F01/status",
                "{\"forkliftId\":\"REAL-F01\",\"status\":\"IDLE\"}");

        receiver.receive(message);

        verify(router).route("forklift/REAL-F01/status", message.getPayload());
    }

    @Test
    void receive_routerThrows_isolatesFailureAndContinuesWithNextMessage() {
        Message<String> first = mqttMessage("forklift/REAL-F01/status", "{\"sequence\":1}");
        Message<String> second = mqttMessage("forklift/REAL-F01/status", "{\"sequence\":2}");
        doThrow(new IllegalStateException("service unavailable"))
                .doNothing()
                .when(router)
                .route(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

        assertThatCode(() -> {
            receiver.receive(first);
            receiver.receive(second);
        }).doesNotThrowAnyException();

        verify(router, times(2)).route(
                org.mockito.ArgumentMatchers.eq("forklift/REAL-F01/status"),
                org.mockito.ArgumentMatchers.anyString());
    }

    private Message<String> mqttMessage(String topic, String payload) {
        return MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.RECEIVED_TOPIC, topic)
                .setHeader(MqttHeaders.RECEIVED_QOS, 1)
                .setHeader(MqttHeaders.RECEIVED_RETAINED, false)
                .build();
    }
}
