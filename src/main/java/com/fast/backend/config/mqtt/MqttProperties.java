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

    public record Topics(
            String forkliftStatus,
            String forkliftLocation,
            String forkliftPath,
            String forkliftCommandResult,
            String forkliftForkStatus,
            String forkliftError,
            String cargoDetected,
            String forkliftCommand,
            String forkliftEmergency
    ) {
    }
}
