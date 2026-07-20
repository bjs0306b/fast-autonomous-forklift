package com.fast.backend.mqtt.outbound;

public class MqttPublishException extends RuntimeException {

    public MqttPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
