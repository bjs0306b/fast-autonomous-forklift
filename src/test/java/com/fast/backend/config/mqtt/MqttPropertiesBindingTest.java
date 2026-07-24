package com.fast.backend.config.mqtt;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml}의 {@code mqtt.*} 값이 {@link MqttProperties}에 실제로 바인딩되는지 검증한다
 * (prompt33.md 6장 "application 설정 바인딩 테스트").
 *
 * <p>{@link ApplicationContextRunner}로 {@code MqttProperties}만 등록한 최소 컨텍스트를 띄운다 —
 * {@code MqttConfig}를 함께 올리면 {@code mqtt.enabled}에 따라 실제 Paho 클라이언트/어댑터 Bean이 생성돼
 * 브로커 접속을 시도할 위험이 있으므로, 프로퍼티 바인딩 자체만 독립적으로 검증한다.
 *
 * <p>MQTT QoS 값은 반드시 <b>MQTT QoS</b>(0/1/2 정수)로 표기한다 — 여기서 검증하는 {@code default-qos}는
 * 구독 시 사용하는 MQTT QoS이며, iOS 등 다른 의미의 "QoS"와 무관하다.
 */
class MqttPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(MqttProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void defaultQosBindsToOneAsConfirmedInApplicationLocalYml() {
        // application-local.yml/application-test.yml의 mqtt.default-qos 값(1)을 그대로 재현한다.
        contextRunner
                .withPropertyValues(
                        "mqtt.broker-url=tcp://localhost:1883",
                        "mqtt.inbound-client-id=test-inbound",
                        "mqtt.outbound-client-id=test-outbound",
                        "mqtt.connection-timeout=10",
                        "mqtt.keep-alive-interval=30",
                        "mqtt.automatic-reconnect=true",
                        "mqtt.clean-session=true",
                        "mqtt.default-qos=1",
                        "mqtt.completion-timeout=5000",
                        "mqtt.recovery-interval=5000",
                        "mqtt.topics.forklift-status=forklift/+/status",
                        "mqtt.topics.forklift-location=forklift/+/location",
                        "mqtt.topics.forklift-path=forklift/+/path",
                        "mqtt.topics.forklift-command-result=forklift/+/command-result",
                        "mqtt.topics.forklift-fork-status=forklift/+/fork-status",
                        "mqtt.topics.forklift-error=forklift/+/error",
                        "mqtt.topics.cargo-detected=cargo/detected",
                        "mqtt.topics.forklift-command=forklift/%s/command",
                        "mqtt.topics.station-measurement=fast/station/+/measurement")
                .run(context -> {
                    MqttProperties properties = context.getBean(MqttProperties.class);
                    assertThat(properties.defaultQos()).isEqualTo(1);
                    assertThat(properties.brokerUrl()).isEqualTo("tcp://localhost:1883");
                    assertThat(properties.topics().forkliftStatus()).isEqualTo("forklift/+/status");
                    assertThat(properties.topics().forkliftLocation()).isEqualTo("forklift/+/location");
                    assertThat(properties.topics().forkliftPath()).isEqualTo("forklift/+/path");
                    assertThat(properties.topics().forkliftCommandResult()).isEqualTo("forklift/+/command-result");
                    assertThat(properties.topics().forkliftForkStatus()).isEqualTo("forklift/+/fork-status");
                    assertThat(properties.topics().forkliftError()).isEqualTo("forklift/+/error");
                    assertThat(properties.topics().cargoDetected()).isEqualTo("cargo/detected");
                    assertThat(properties.topics().forkliftCommand()).isEqualTo("forklift/%s/command");
                    assertThat(properties.topics().stationMeasurement()).isEqualTo("fast/station/+/measurement");
                });
    }

    @Test
    void topicsRecord_hasNoEmergencyFieldAnymore() {
        // prompt32.md에서 forklift/{id}/emergency 전용 토픽을 제거했다 — Topics record에 그 필드가
        // 부활하지 않았는지 회귀 검증한다(리플렉션으로 컴포넌트 이름만 확인, 값 바인딩과 무관).
        var componentNames = java.util.Arrays.stream(MqttProperties.Topics.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(componentNames).doesNotContain("forkliftEmergency");
        assertThat(componentNames).containsExactlyInAnyOrder(
                "forkliftStatus", "forkliftLocation", "forkliftPath", "forkliftCommandResult",
                "forkliftForkStatus", "forkliftError", "cargoDetected", "forkliftCommand", "stationMeasurement");
    }
}
