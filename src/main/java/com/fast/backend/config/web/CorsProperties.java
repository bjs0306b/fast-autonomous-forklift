package com.fast.backend.config.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * REST API의 CORS 허용 Origin 설정.
 *
 * <p>기존 {@link com.fast.backend.config.websocket.WebSocketProperties}가 STOMP endpoint의 Origin을
 * 설정값으로 분리한 것과 <b>같은 방식</b>을 REST에도 적용한다 — 코드에 Origin을 하드코딩하지 않는다.
 *
 * <p><b>기본값은 비어 있다(=CORS 비활성)</b>. 값을 지정한 프로필에서만 CORS 응답 헤더가 나간다.
 * 운영에서 실수로 모든 Origin이 열리는 것을 막기 위해 {@code *}를 기본값으로 두지 않았다
 * (WebSocket 쪽 기본값이 {@code *}인 것은 기존 정책이라 그대로 두었다).
 *
 * <p>개발 환경 값은 {@code application-local.yml}에 있으며 {@code CORS_ALLOWED_ORIGINS} 환경변수로
 * 덮어쓸 수 있다(쉼표 구분).
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
}
