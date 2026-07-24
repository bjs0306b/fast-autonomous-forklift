package com.fast.backend.config;

import com.fast.backend.common.time.CommunicationTimeModule;
import com.fasterxml.jackson.databind.Module;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 통신 시각 규격(prompt32.md 1장 6번)에 필요한 Jackson 설정.
 *
 * <p>직렬화는 Spring Boot 기본 설정({@code jackson-datatype-jsr310} +
 * {@code WRITE_DATES_AS_TIMESTAMPS=false})으로 이미 ISO-8601 오프셋 문자열
 * ({@code 2026-07-23T11:20:27+09:00})이 나가므로 별도 설정이 필요 없다.
 *
 * <p>역직렬화는 {@link CommunicationTimeModule}을 등록해 <b>오프셋이 빠진 과도기 payload</b>도 받아
 * 들인다 — 근거와 제거 조건은 그 클래스 Javadoc 참고.
 *
 * <p>{@code application.yml}의 {@code spring.jackson.deserialization.adjust-dates-to-context-time-zone=false}는
 * 그대로 유지한다(스테이션 도메인의 오프셋 보존에 필요).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module communicationTimeModule() {
        return new CommunicationTimeModule();
    }
}
