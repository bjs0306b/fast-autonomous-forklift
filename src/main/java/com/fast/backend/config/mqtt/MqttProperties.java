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

    /**
     * MQTT 토픽 패턴.
     *
     * <p>{@code forkliftEmergency}({@code forklift/%s/emergency})는 <b>제거됐다</b>
     * (prompt32.md 1장 7번 "기존 forklift/{id}/emergency 전용 토픽은 사용하지 않는다"). 제거 전
     * 영향도를 확인한 결과 이 토픽을 발행·구독하는 프로덕션 코드가 하나도 없어(설정과
     * {@code MqttTopics}에 정의만 존재) 안전하게 삭제할 수 있었다. 비상 정지는 이제 공통
     * {@code forkliftCommand} 토픽에 {@code targetSystem=ALL}·{@code commandCategory=SAFETY}·
     * {@code command=EMERGENCY_STOP} 조합으로 발행된다.
     */
    public record Topics(
            String forkliftStatus,
            String forkliftLocation,
            String forkliftPath,
            String forkliftCommandResult,
            String forkliftForkStatus,
            String forkliftError,
            String cargoDetected,
            String forkliftCommand,
            String stationMeasurement
    ) {
    }
}
