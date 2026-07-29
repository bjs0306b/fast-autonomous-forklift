package com.fast.backend.config.mqtt;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.util.Arrays;

/**
 * MQTT v3(Eclipse Paho) 연결, 구독(Inbound), 발행(Outbound) 인프라를 구성한다.
 * Inbound/Outbound는 {@link #mqttClientFactory()} 하나를 공유하되, 서로 다른 Client ID로 접속한다.
 *
 * <p>실제로 Broker에 접속하는 Client Factory, Inbound Adapter, Outbound Handler는
 * {@code mqtt.enabled}(기본값 true)이 false일 때 생성되지 않는다. {@code @ServiceActivator}가 참조하는
 * 채널 이름은 Spring Integration이 암시적으로 생성할 수 있지만, Paho 연결 Bean이 없으므로 실제 Broker
 * 접속은 발생하지 않는다. MQTT를 다루지 않는
 * {@code @SpringBootTest}(예: {@code FastBackendApplicationTests})가 매번 실제 Broker에 접속하면서
 * 같은 Client ID로 충돌(Lost connection)하는 문제를 막기 위해, 테스트 프로필({@code application-test.yml})에서
 * 이 값을 false로 둔다. {@link MqttProperties} 바인딩 자체는 조건과 무관하게 항상 활성화된다.
 */
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    private final MqttProperties mqttProperties;
    private final MqttTopics mqttTopics;

    public MqttConfig(MqttProperties mqttProperties, MqttTopics mqttTopics) {
        this.mqttProperties = mqttProperties;
        this.mqttTopics = mqttTopics;
    }

    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        factory.setConnectionOptions(mqttConnectOptions());
        log.info("MQTT client factory configured: brokerUrl={}, automaticReconnect={}, cleanSession={}",
                mqttProperties.brokerUrl(), mqttProperties.automaticReconnect(), mqttProperties.cleanSession());
        return factory;
    }

    private MqttConnectOptions mqttConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[] {mqttProperties.brokerUrl()});
        if (mqttProperties.username() != null && !mqttProperties.username().isBlank()) {
            options.setUserName(mqttProperties.username());
        }
        if (mqttProperties.password() != null && !mqttProperties.password().isBlank()) {
            options.setPassword(mqttProperties.password().toCharArray());
        }
        options.setAutomaticReconnect(mqttProperties.automaticReconnect());
        options.setCleanSession(mqttProperties.cleanSession());
        options.setConnectionTimeout(mqttProperties.connectionTimeout());
        options.setKeepAliveInterval(mqttProperties.keepAliveInterval());
        // 이 Spring Integration 버전(6.3.4)의 MqttPahoMessageDrivenChannelAdapter에는
        // 과거 존재하던 setRecoveryInterval(int)가 더 이상 없다. 대신 Paho의 자동 재연결
        // 백오프 상한(maxReconnectDelay, ms)에 recovery-interval 설정값을 매핑해 사용한다.
        // 주의(실측): 이 값은 "몇 ms마다 재시도"라는 고정 주기가 아니라 지수 백오프의 상한이다.
        // 로컬에서 Broker를 강제 종료했다가 재기동해 실측한 결과, 연결 끊김 감지부터 자동
        // 재구독 성공까지 recovery-interval(5000ms)보다 훨씬 긴 약 88초가 걸렸다(2026-07-20,
        // prompt5.md 후속 검증). recovery-interval을 줄인다고 재연결이 그만큼 빨라진다고
        // 단정하지 말고, 실제 장애 상황에서 재검증해야 한다.
        options.setMaxReconnectDelay((int) mqttProperties.recoveryInterval());
        return options;
    }

    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MessageChannel mqttErrorChannel() {
        return new DirectChannel();
    }

    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    public MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter() {
        String[] topics = {
                mqttTopics.forkliftStatusSubscribeTopic(),
                mqttTopics.forkliftLocationSubscribeTopic(),
                mqttTopics.forkliftPathSubscribeTopic(),
                mqttTopics.forkliftCommandResultSubscribeTopic(),
                mqttTopics.forkliftForkStatusSubscribeTopic(),
                mqttTopics.forkliftErrorSubscribeTopic(),
                mqttTopics.cargoDetectedTopic(),
                mqttTopics.stationMeasurementSubscribeTopic(),
                mqttTopics.forkliftLoadSafetySubscribeTopic()
        };
        int[] qosLevels = new int[topics.length];
        Arrays.fill(qosLevels, mqttProperties.defaultQos());

        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                mqttProperties.inboundClientId(), mqttClientFactory(), topics);
        adapter.setQos(qosLevels);
        adapter.setCompletionTimeout(mqttProperties.completionTimeout());
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setOutputChannel(mqttInputChannel());
        adapter.setErrorChannel(mqttErrorChannel());
        adapter.setDisconnectCompletionTimeout(mqttProperties.completionTimeout());

        log.info("MQTT inbound adapter starting: clientId={}, topics={}, qos={}",
                mqttProperties.inboundClientId(), Arrays.toString(topics), Arrays.toString(qosLevels));
        return adapter;
    }

    @Bean
    @ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutboundHandler() {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(
                mqttProperties.outboundClientId(), mqttClientFactory());
        handler.setAsync(true);
        handler.setDefaultQos(mqttProperties.defaultQos());
        handler.setDefaultRetained(false);
        handler.setCompletionTimeout(mqttProperties.completionTimeout());
        handler.setDisconnectCompletionTimeout(mqttProperties.completionTimeout());

        log.info("MQTT outbound handler configured: clientId={}, defaultQos={}",
                mqttProperties.outboundClientId(), mqttProperties.defaultQos());
        return handler;
    }
}
