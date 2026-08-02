package com.fast.backend.station.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 측정 세션 점유 정책 설정.
 *
 * <p>{@code ttlSeconds}: 세션 점유가 유효한 시간(초, 기본 60초). 측정 작업자가 정상 결과를
 * 보내지 못한 채 종료되면 세션 해제가 일어나지 않아 단일 설비
 * 잠금이 <b>영구히</b> 남는다. 이 시간이 지난 점유는 다음 {@code openSession} 이 회수한다.
 *
 * <p>값을 코드에 박지 않는 이유는 현장마다 한 번의 측정에 걸리는 시간이 다르기 때문이다. 너무 짧으면
 * 정상 측정 중인 세션을 빼앗고, 너무 길면 복구가 늦어진다. 환경변수
 * {@code STATION_SESSION_TTL_SECONDS} 로 재정의할 수 있다.
 *
 * <p><b>스케줄러를 두지 않는다.</b> 만료 판정은 새 세션을 여는 시점에만 필요하므로, 주기적으로 도는
 * 작업을 추가하는 대신 {@code openSession} 의 조건부 UPDATE 안에서 함께 처리한다 — 판정과 점유가
 * 한 문장이라 그 사이에 다른 요청이 끼어들 틈도 없다.
 */
@ConfigurationProperties(prefix = "station.session")
public record StationSessionProperties(
        Long ttlSeconds
) {

    /** 설정을 비워 두었을 때 쓰는 기본 TTL(초). */
    public static final long DEFAULT_TTL_SECONDS = 60L;

    /**
     * 상한. TTL 을 하루보다 길게 잡으면 "자동 복구"라는 기능의 의미가 사라져, 설정 오타(예: 초 단위
     * 자리에 밀리초를 넣음)를 기동 시점에 잡는다.
     */
    private static final long MAX_TTL_SECONDS = 86_400L;

    public StationSessionProperties {
        if (ttlSeconds == null) {
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "station.session.ttl-seconds must be in (0, " + MAX_TTL_SECONDS + "]: " + ttlSeconds);
        }
    }

    public Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }
}
