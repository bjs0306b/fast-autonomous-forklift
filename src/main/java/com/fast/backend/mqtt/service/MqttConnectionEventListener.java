package com.fast.backend.mqtt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.integration.mqtt.core.MqttPahoComponent;
import org.springframework.integration.mqtt.event.MqttConnectionFailedEvent;
import org.springframework.integration.mqtt.event.MqttMessageDeliveredEvent;
import org.springframework.integration.mqtt.event.MqttMessageDeliveryEvent;
import org.springframework.integration.mqtt.event.MqttMessageSentEvent;
import org.springframework.integration.mqtt.event.MqttSubscribedEvent;
import org.springframework.integration.support.context.NamedComponent;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Spring Integration MQTT(6.3.4)가 실제로 발행하는 이벤트만 구독한다.
 * 이 버전에는 MqttMessageNotDeliveredEvent 클래스가 존재하지 않아 의도적으로 사용하지 않았다.
 */
@Component
public class MqttConnectionEventListener {

    private static final Logger log = LoggerFactory.getLogger(MqttConnectionEventListener.class);

    @EventListener
    public void onSubscribed(MqttSubscribedEvent event) {
        log.info("MQTT subscribed: bean={}, message={}", beanNameOf(event.getSource()), event.getMessage());
    }

    @EventListener
    public void onConnectionFailed(MqttConnectionFailedEvent event) {
        log.error("MQTT connection failed: bean={}, brokerUri={}, cause={}",
                beanNameOf(event.getSource()),
                brokerUriOf(event.getSource()),
                event.getCause() != null ? event.getCause().getMessage() : "N/A");
    }

    @EventListener
    public void onMessageSent(MqttMessageSentEvent event) {
        logDelivery("MQTT message sent", event);
    }

    @EventListener
    public void onMessageDelivered(MqttMessageDeliveredEvent event) {
        logDelivery("MQTT message delivered", event);
    }

    private void logDelivery(String label, MqttMessageDeliveryEvent event) {
        log.info("{}: bean={}, clientId={}, messageId={}",
                label, beanNameOf(event.getSource()), event.getClientId(), event.getMessageId());
    }

    private String beanNameOf(Object source) {
        return source instanceof NamedComponent namedComponent ? namedComponent.getBeanName() : String.valueOf(source);
    }

    private String brokerUriOf(Object source) {
        if (source instanceof MqttPahoComponent mqttPahoComponent && mqttPahoComponent.getConnectionInfo() != null) {
            String[] serverURIs = mqttPahoComponent.getConnectionInfo().getServerURIs();
            return serverURIs != null ? Arrays.toString(serverURIs) : "N/A";
        }
        return "N/A";
    }
}
