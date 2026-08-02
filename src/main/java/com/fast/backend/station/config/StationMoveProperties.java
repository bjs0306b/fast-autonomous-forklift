package com.fast.backend.station.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 측정 위치로 이동한 뒤 MOVE 완료 결과를 기다리는 시간 제한 설정. */
@ConfigurationProperties(prefix = "station.move")
public record StationMoveProperties(Long ttlSeconds) {

    public static final long DEFAULT_TTL_SECONDS = 300L;
    private static final long MAX_TTL_SECONDS = 86_400L;

    public StationMoveProperties {
        if (ttlSeconds == null) {
            ttlSeconds = DEFAULT_TTL_SECONDS;
        }
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException(
                    "station.move.ttl-seconds must be in (0, " + MAX_TTL_SECONDS + "]: " + ttlSeconds);
        }
    }

    public Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }
}
