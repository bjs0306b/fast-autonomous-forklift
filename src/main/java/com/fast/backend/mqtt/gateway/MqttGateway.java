package com.fast.backend.mqtt.gateway;

import org.springframework.integration.annotation.MessagingGateway;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.handler.annotation.Header;

/**
 * MQTT 발행 전용 Gateway. mqttOutboundChannel로 메시지를 보내면
 * {@code mqttOutboundHandler}(MqttPahoMessageHandler)가 실제 발행을 수행한다.
 * Controller나 도메인 Service는 이 인터페이스를 직접 호출하지 않고 {@code MqttPublisher}를 거쳐야 한다.
 */
@MessagingGateway(defaultRequestChannel = "mqttOutboundChannel")
public interface MqttGateway {

    void publish(
            String payload,
            @Header(MqttHeaders.TOPIC) String topic,
            @Header(MqttHeaders.QOS) int qos,
            @Header(MqttHeaders.RETAINED) boolean retained
    );
}
