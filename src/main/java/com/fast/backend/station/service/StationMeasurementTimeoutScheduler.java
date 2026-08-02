package com.fast.backend.station.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** TTL 정리 서비스를 주기적으로 실행한다. */
@Component
public class StationMeasurementTimeoutScheduler {

    private final StationMeasurementTimeoutService timeoutService;

    public StationMeasurementTimeoutScheduler(StationMeasurementTimeoutService timeoutService) {
        this.timeoutService = timeoutService;
    }

    @Scheduled(fixedDelayString = "${station.session.cleanup-interval-ms:5000}")
    public void cleanup() {
        timeoutService.expireTimedOutMeasurements();
    }
}
