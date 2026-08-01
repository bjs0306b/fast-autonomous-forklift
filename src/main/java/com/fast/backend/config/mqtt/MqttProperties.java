package com.fast.backend.config.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(
        String brokerUrl,
        String username,
        String password,
        String inboundClientId,
        String outboundClientId,
        int connectionTimeout,
        int keepAliveInterval,
        boolean automaticReconnect,
        boolean cleanSession,
        int defaultQos,
        long completionTimeout,
        long recoveryInterval,
        Topics topics
) {

    /** 현재 ROS2 및 화물 측정 계약에서 사용하는 MQTT 토픽. */
    public record Topics(
            String forkliftStatus,
            String forkliftLocation,
            String forkliftPath,
            String forkliftCommandResult,
            String stationMeasurement,
            String forkliftCommand
    ) {
    }
}
