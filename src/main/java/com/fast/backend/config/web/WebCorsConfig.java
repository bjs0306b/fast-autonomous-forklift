package com.fast.backend.config.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * REST API CORS 설정.
 *
 * <p><b>추가한 이유</b>: 이 프로젝트에는 CORS 설정이 전혀 없었다. 그래서 브라우저 프론트
 * (http://localhost:3000)가 백엔드(http://localhost:8080)를 호출하면 preflight(OPTIONS)가 <b>403</b>으로
 * 거부되고, 실제 GET 응답에도 {@code Access-Control-Allow-Origin} 헤더가 없어 브라우저가 응답을 차단했다.
 * 서버 대 서버 호출(curl 등)에서는 드러나지 않고 브라우저에서만 실패하는 종류의 결함이다.
 *
 * <p><b>범위를 좁게 유지한다</b>
 * <ul>
 *   <li>경로: {@code /api/**}만. 정적 리소스나 STOMP endpoint({@code /ws})는 대상이 아니다
 *       — {@code /ws}의 Origin 검사는 {@code WebSocketConfig}가 이미 따로 담당한다.</li>
 *   <li>Origin: 설정된 값만. 값이 없으면 <b>CORS 자체를 등록하지 않는다</b>(운영 기본값 = 비활성).</li>
 *   <li>Method: GET / POST / OPTIONS. 관제 화면이 쓰는 조회와 안전 명령 발행에 필요한 최소 범위다.
 *       PATCH/PUT(차량 활성 변경, 작업 배정 등)은 관제 화면 기능이 아니라 넣지 않았다 —
 *       필요해지면 그때 명시적으로 넓힌다.</li>
 *   <li>Header: Content-Type / Accept.</li>
 *   <li>{@code allowCredentials}는 켜지 않는다 — 현재 인증이 없어 쿠키·인증 헤더를 쓰지 않는다.
 *       (켜는 순간 Origin에 와일드카드를 쓸 수 없게 되는 제약도 함께 생긴다.)</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebCorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebCorsConfig.class);

    private final CorsProperties corsProperties;

    public WebCorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var allowedOrigins = corsProperties.allowedOrigins();
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            log.info("CORS disabled: no cors.allowed-origins configured");
            return;
        }

        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept")
                .allowCredentials(false)
                .maxAge(3600);

        log.info("CORS enabled for /api/**: allowedOrigins={}", allowedOrigins);
    }
}
