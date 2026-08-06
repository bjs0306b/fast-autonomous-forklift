package com.fast.backend.traffic.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 교통 관제 설정 바인딩 (FR-502-1a).
 *
 * <p>바인딩은 기능 on/off 와 무관하게 항상 활성화한다 — {@code traffic.enabled=false} 여도 설정값을
 * 읽을 수 있어야 진단·테스트에서 "무슨 값으로 꺼져 있는지"를 확인할 수 있다. 실제로 차량에 명령을
 * 보내는 것은 {@code TrafficControlScheduler} 이며 그쪽이 {@code enabled=true} 를 요구한다.
 */
@Configuration
@EnableConfigurationProperties(TrafficControlProperties.class)
public class TrafficControlConfig {
}
