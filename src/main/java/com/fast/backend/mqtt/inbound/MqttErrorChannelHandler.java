package com.fast.backend.mqtt.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.stereotype.Component;

/**
 * Inbound 변환/처리 과정에서 발생한 예외를 mqttErrorChannel을 통해 전용으로 수신한다.
 * 이 핸들러 자체에서 다시 예외가 발생해 애플리케이션에 영향을 주지 않도록 방어적으로 작성한다.
 */
@Component
public class MqttErrorChannelHandler {

    private static final Logger log = LoggerFactory.getLogger(MqttErrorChannelHandler.class);

    @ServiceActivator(inputChannel = "mqttErrorChannel")
    public void handleError(ErrorMessage errorMessage) {
        try {
            Throwable exception = errorMessage.getPayload();
            Message<?> originalMessage = errorMessage.getOriginalMessage();
            Object failedTopic = originalMessage != null
                    ? originalMessage.getHeaders().get(MqttHeaders.RECEIVED_TOPIC)
                    : "unknown";
            Throwable rootCause = NestedExceptionUtils.getRootCause(exception);

            log.error("MQTT inbound processing failed: topic={}, exceptionClass={}, message={}, rootCause={}",
                    failedTopic,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    rootCause != null ? rootCause.getMessage() : "N/A");
        } catch (Exception unexpected) {
            log.error("Failed to log MQTT error message due to an internal error", unexpected);
        }
    }
}
