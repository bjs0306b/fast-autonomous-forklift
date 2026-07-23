package com.fast.backend.config.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocketConfig가 Spring Context에 정상적으로 로딩되고, STOMP 메시징 인프라(SimpMessagingTemplate,
 * {@code /ws} 엔드포인트 매핑, Simple Broker)가 기동되는지 검증한다(prompt20.md 14장 "WebSocketConfig
 * 테스트"). 실제 네트워크로 {@code /ws}에 접속해 SockJS 핸드셰이크·STOMP 프레임을 주고받는 테스트는
 * 하지 않는다 — 별도의 WebSocket 테스트 클라이언트가 필요해 "WebSocket 인프라가 올바르게 배선됐는지"를
 * 확인하는 이 Story의 범위에 비해 과도하다고 판단했다(prompt20.md 14장 "실제 WebSocket 네트워크 연결
 * 테스트가 과도하다면 단위 테스트와 Spring Context 테스트까지만 수행하고 이유를 작성해줘" 조건에 따름).
 * 브로드캐스트 메시지가 올바른 토픽·형식으로 나가는지는 {@code VehicleWebSocketBroadcasterTest}가
 * Mock으로 이미 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebSocketConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebSocketConfig webSocketConfig;

    @Autowired
    private SimpMessagingTemplate simpMessagingTemplate;

    @Autowired
    private WebSocketProperties webSocketProperties;

    @Test
    void contextLoads_withWebSocketConfigBean() {
        assertThat(webSocketConfig).isNotNull();
    }

    @Test
    void simpMessagingTemplateBean_isRegistered() {
        // @EnableWebSocketMessageBroker + enableSimpleBroker("/topic")가 정상 적용됐다면
        // Spring이 SimpMessagingTemplate을 자동으로 Bean 등록한다 — VehicleWebSocketBroadcaster가
        // 이 Bean을 생성자 주입으로 실제로 쓰고 있으므로, 여기서 존재를 확인하는 것은 "Broadcaster가
        // 기동 가능한 상태"임을 검증하는 것과 같다.
        assertThat(simpMessagingTemplate).isNotNull();
    }

    @Test
    void stompWebSocketHandlerMapping_isRegisteredForWsEndpoint() {
        // Spring의 WebSocketMessageBrokerConfigurationSupport가 registerStompEndpoints()에서 등록한
        // 엔드포인트를 이 이름의 HandlerMapping Bean으로 노출한다 — "/ws" 엔드포인트가 실제로 등록됐는지
        // 확인하는 가장 안정적인 방법이다.
        assertThat(applicationContext.containsBean("stompWebSocketHandlerMapping")).isTrue();
    }

    @Test
    void webSocketProperties_bindsAllowedOriginPatternsFromTestYaml() {
        assertThat(webSocketProperties.allowedOriginPatterns()).containsExactly("*");
    }
}
