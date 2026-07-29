package com.fast.backend.config.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 백엔드가 구독할 때 실제로 어떤 MQTT QoS를 쓰는지 검증한다(prompt33.md 6장 "상태/위치/경로/포크상태/
 * 오류/command-result 구독 QoS 확인"). "QoS"는 항상 MQTT QoS(0/1/2 정수)를 의미하며, iOS와는 무관하다.
 *
 * <p>{@link MqttConfig}를 Spring 컨테이너 없이 직접 인스턴스화해서 {@code @Bean} 메서드를 <b>메서드
 * 호출로만</b> 실행한다 — {@code @ConditionalOnProperty}는 Spring이 Bean을 등록할지 결정하는
 * 메타데이터일 뿐이라 직접 호출에는 영향을 주지 않는다. 이 방식이면 실제 브로커에 접속하지 않고도
 * {@link MqttPahoMessageDrivenChannelAdapter}가 각 토픽에 어떤 QoS를 들고 있는지 그 객체의 <b>공개
 * API</b>({@code getTopic()}/{@code getQos()})로 바로 확인할 수 있다(리플렉션 불필요).
 *
 * <p>QoS 값 자체가 실제로 어디서 오는지는 {@code MqttConfig.mqttInboundAdapter()} 코드 근거:
 * {@code Arrays.fill(qosLevels, mqttProperties.defaultQos())} — 8개 구독 토픽 전부가 설정값
 * {@code mqtt.default-qos} 하나를 공유한다.
 */
class MqttConfigTest {

    private MqttConfig mqttConfig;
    private MqttProperties mqttProperties;

    @BeforeEach
    void setUp() {
        MqttProperties.Topics topics = new MqttProperties.Topics(
                "forklift/+/status", "forklift/+/location", "forklift/+/path",
                "forklift/+/command-result", "forklift/+/fork-status", "forklift/+/error",
                "cargo/detected", "forklift/%s/command", "fast/station/+/measurement",
                "forklift/+/load-safety");
        mqttProperties = new MqttProperties(
                "tcp://localhost:1883", null, null, "test-inbound", "test-outbound",
                10, 30, true, true, 1, 5000L, 5000L, topics);
        mqttConfig = new MqttConfig(mqttProperties, new MqttTopics(mqttProperties));
    }

    @Test
    void inboundAdapter_subscribesToAllNineConfirmedTopics() {
        MqttPahoMessageDrivenChannelAdapter adapter = mqttConfig.mqttInboundAdapter();

        // prompt63.md 4장으로 적재 화물 안전 구독이 추가되어 8 → 9개가 됐다.
        assertThat(adapter.getTopic()).containsExactlyInAnyOrder(
                "forklift/+/status", "forklift/+/location", "forklift/+/path",
                "forklift/+/command-result", "forklift/+/fork-status", "forklift/+/error",
                "cargo/detected", "fast/station/+/measurement", "forklift/+/load-safety");
    }

    @Test
    void inboundAdapter_everySubscribedTopicUsesMqttQos1() {
        // 상태·위치·경로·command-result·포크상태·오류·AI·스테이션·적재안전 구독 QoS를 한 번에 확인한다.
        MqttPahoMessageDrivenChannelAdapter adapter = mqttConfig.mqttInboundAdapter();

        Map<String, Integer> topicToQos = topicToQos(adapter);

        assertThat(topicToQos).containsOnlyKeys(
                "forklift/+/status", "forklift/+/location", "forklift/+/path",
                "forklift/+/command-result", "forklift/+/fork-status", "forklift/+/error",
                "cargo/detected", "fast/station/+/measurement", "forklift/+/load-safety");
        assertThat(topicToQos.values()).allMatch(qos -> qos == 1, "MQTT QoS 1이어야 한다");
    }

    @Test
    void statusTopic_subscribeMqttQosIsOne() {
        assertThat(topicToQos(mqttConfig.mqttInboundAdapter()).get("forklift/+/status")).isEqualTo(1);
    }

    @Test
    void locationTopic_subscribeMqttQosIsOne() {
        assertThat(topicToQos(mqttConfig.mqttInboundAdapter()).get("forklift/+/location")).isEqualTo(1);
    }

    @Test
    void pathTopic_subscribeMqttQosIsOne() {
        assertThat(topicToQos(mqttConfig.mqttInboundAdapter()).get("forklift/+/path")).isEqualTo(1);
    }

    @Test
    void commandResultTopic_subscribeMqttQosIsOne() {
        assertThat(topicToQos(mqttConfig.mqttInboundAdapter()).get("forklift/+/command-result")).isEqualTo(1);
    }

    @Test
    void forkStatusTopic_subscribeMqttQosIsOne() {
        assertThat(topicToQos(mqttConfig.mqttInboundAdapter()).get("forklift/+/fork-status")).isEqualTo(1);
    }

    @Test
    void errorTopic_subscribeMqttQosIsOne() {
        assertThat(topicToQos(mqttConfig.mqttInboundAdapter()).get("forklift/+/error")).isEqualTo(1);
    }

    @Test
    void inboundAdapter_qosFollowsMqttDefaultQosPropertyNotAHardcodedConstant() {
        // mqtt.default-qos를 2로 바꾸면 8개 토픽 전부 2로 바뀌어야 한다 — 구독 QoS의 출처가
        // application.yml의 설정값임을 증명한다(코드 상수가 아님).
        MqttProperties.Topics topics = mqttProperties.topics();
        MqttProperties changed = new MqttProperties(
                mqttProperties.brokerUrl(), null, null, "in", "out",
                10, 30, true, true, 2, 5000L, 5000L, topics);
        MqttConfig configWithQos2 = new MqttConfig(changed, new MqttTopics(changed));

        assertThat(topicToQos(configWithQos2.mqttInboundAdapter()).values()).allMatch(qos -> qos == 2);
    }

    @Test
    void clientFactory_appliesConnectionAndReconnectProperties() {
        DefaultMqttPahoClientFactory factory =
                (DefaultMqttPahoClientFactory) mqttConfig.mqttClientFactory();
        MqttConnectOptions options = factory.getConnectionOptions();

        assertThat(options.getServerURIs()).containsExactly("tcp://localhost:1883");
        assertThat(options.isAutomaticReconnect()).isTrue();
        assertThat(options.isCleanSession()).isTrue();
        assertThat(options.getConnectionTimeout()).isEqualTo(10);
        assertThat(options.getKeepAliveInterval()).isEqualTo(30);
        assertThat(options.getMaxReconnectDelay()).isEqualTo(5000);
    }

    private Map<String, Integer> topicToQos(MqttPahoMessageDrivenChannelAdapter adapter) {
        String[] topicNames = adapter.getTopic();
        int[] qosValues = adapter.getQos();
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < topicNames.length; i++) {
            result.put(topicNames[i], qosValues[i]);
        }
        return result;
    }
}
