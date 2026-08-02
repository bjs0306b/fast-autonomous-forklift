package com.fast.backend.station.config;

import com.fast.backend.common.time.CommunicationTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 측정 세션 설정 등록(prompt106).
 *
 * <p><b>{@link Clock} 을 빈으로 두는 이유</b>: TTL 만료 판정은 "지금이 몇 시인가"에 의존하는데,
 * {@code LocalDateTime.now()} 를 직접 부르면 테스트에서 TTL 시간을 실제로 기다리는 것 말고는 검증할
 * 방법이 없다. Clock 을 주입받으면 테스트가 {@link Clock#fixed} 로 시간을 앞뒤로 옮겨
 * 만료/미만료를 즉시 재현할 수 있다.
 *
 * <p>기존 {@link CommunicationTime}(정적 유틸)은 그대로 둔다 — 통신 규격의 오프셋 변환이 목적이라
 * 역할이 다르고, 정적 메서드라 시간을 제어할 수 없다. 여기서는 그 클래스가 정한 <b>같은 타임존</b>
 * (Asia/Seoul)을 Clock 에 물려 두 경로가 서로 다른 시각을 쓰지 않게 한다.
 *
 * <p>{@code @ConditionalOnMissingBean} 이라, 다른 곳에서 Clock 을 정의하거나 테스트가 고정 Clock 을
 * 등록하면 그쪽이 우선한다.
 */
@Configuration
@EnableConfigurationProperties({StationSessionProperties.class, StationMoveProperties.class})
public class StationSessionConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock stationClock() {
        return Clock.system(CommunicationTime.ZONE);
    }
}
