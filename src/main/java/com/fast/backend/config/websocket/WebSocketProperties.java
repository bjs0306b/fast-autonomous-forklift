package com.fast.backend.config.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket STOMP endpoint의 허용 Origin을 설정값으로 분리한다(prompt20.md 13장 "가능하면 설정값으로
 * 분리해줘"). 기본값(application-local.yml)은 기존 동작과 동일하게 {@code "*"}(전체 허용)을 유지한다 —
 * 이번 작업은 CORS 정책 자체를 바꾸는 것이 아니라 하드코딩된 값을 설정으로 옮기는 것이 목적이기 때문이다.
 *
 * <p>운영 환경에서는 반드시 실제 프론트엔드 배포 Origin으로 좁혀야 한다(예:
 * {@code https://fast.example.com}). {@code *}를 운영에 그대로 쓰면 어떤 사이트에서든 이 WebSocket에
 * 연결할 수 있게 된다({@link WebSocketConfig} Javadoc 참고).
 */
@ConfigurationProperties(prefix = "websocket")
public record WebSocketProperties(
        String[] allowedOriginPatterns
) {
}
