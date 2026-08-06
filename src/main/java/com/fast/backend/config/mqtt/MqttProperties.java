package com.fast.backend.config.mqtt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mqtt")
public record MqttProperties(
        String brokerUrl,
        String username,
        String password,

        /**
         * {@code ssl://} 브로커(EC2)에 접속할 때 신뢰할 CA 인증서 경로. 비워 두면(로컬 기본값)
         * JVM 기본 트러스트스토어를 그대로 쓴다 — {@code Dockerfile.backend} 가 빌드 시점에
         * {@code infra/mqtt-ca.crt} 를 JVM cacerts 에 이미 심어 두므로, 운영 컨테이너는 이 값을
         * 비워 둬도 EC2 브로커를 신뢰한다. 로컬 IDE 실행은 cacerts 를 건드리지 않으므로, 로컬에서
         * EC2 브로커에 붙으려면 이 값을 {@code infra/mqtt-ca.crt} 로 지정해야 한다
         * ({@code MQTT_CA_CERT_PATH}). {@code tcp://} 브로커(로컬 mosquitto)에는 영향이 없다.
         */
        String caCertPath,
        boolean sslHostnameVerificationEnabled,
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

    /**
     * 현재 ROS2 및 화물 측정 계약에서 사용하는 MQTT 토픽.
     *
     * @param isaacTelemetry Isaac Sim 차량 telemetry({@code fast/v1/vehicle/+/telemetry}). ROS2 위치
     *                       토픽과 <b>별개로 함께 구독</b>한다 — 두 시뮬/실물 경로가 동시에 살아 있어야 한다
     */
    public record Topics(
            String forkliftStatus,
            String forkliftLocation,
            String forkliftPath,
            String forkliftCommandResult,
            String stationMeasureRequest,
            String forkliftCommand,
            String isaacTelemetry,
            String isaacEvent,
            String forkliftArrived
    ) {
    }
}
