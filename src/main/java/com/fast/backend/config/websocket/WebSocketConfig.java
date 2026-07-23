package com.fast.backend.config.websocket;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket 골격(prompt16.md 15장 "STOMP WebSocket 브로드캐스트 골격"). prompt20.md 4~5장에서
 * 차량 상태·위치·명령 결과 실시간 전송으로 목적을 확장했지만, 이 설정 자체(엔드포인트/브로커 경로)는
 * prompt16.md에서 이미 요구사항을 만족하고 있어 이번 작업에서 바꾸지 않았다 — 5장의
 * {@code allowedOriginPatterns}만 설정값으로 분리했다({@link WebSocketProperties}).
 *
 * <p><b>SockJS를 선택한 이유</b>: 엔드포인트는 순수 WebSocket이 아니라 SockJS({@code withSockJS()})를
 * 사용한다. 사내망 프록시나 오래된 브라우저에서 WebSocket 업그레이드가 막히는 환경에서도 폴백(long
 * polling 등)으로 연결을 유지하기 위한 기존 결정이며, 이번 작업에서 재검토했지만 바꿀 이유가 없어
 * 그대로 유지했다. 이 선택 때문에 프론트엔드는 {@code brokerURL: "ws://..."}가 아니라
 * {@code webSocketFactory: () => new SockJS("http://...")}로 연결해야 한다(prompt20.md 16장 예시 참고
 * — prompt20.md 자체 예시는 순수 WebSocket 형태라 이 프로젝트의 실제 구현과 맞지 않아 수정해서 제공한다).
 *
 * <p><b>운영 환경 개선 필요(prompt20.md 13장, 17-10장 위험 요소)</b>: {@link WebSocketProperties}의
 * 기본값은 여전히 {@code "*"}(전체 허용)이다. 운영 배포 시에는 반드시 실제 프론트엔드 Origin으로 좁혀야
 * 한다 — 지금은 프론트엔드 배포 주소가 확정되지 않아 로컬 개발 편의를 위해 넓게 열어둔 상태다.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketProperties webSocketProperties;

    public WebSocketConfig(WebSocketProperties webSocketProperties) {
        this.webSocketProperties = webSocketProperties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(webSocketProperties.allowedOriginPatterns())
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
