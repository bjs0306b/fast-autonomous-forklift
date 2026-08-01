package com.fast.backend.station.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 측정 프로그램에 전달할 재시도 정책. 실제 반복 촬영은 AI 측정 프로그램이 수행한다. */
@ConfigurationProperties(prefix = "station.measurement-request")
public record StationMeasurementRequestProperties(Integer maxAttempts) {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    public StationMeasurementRequestProperties {
        if (maxAttempts == null) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException(
                    "station.measurement-request.max-attempts must be in [1, 10]: " + maxAttempts);
        }
    }
}
