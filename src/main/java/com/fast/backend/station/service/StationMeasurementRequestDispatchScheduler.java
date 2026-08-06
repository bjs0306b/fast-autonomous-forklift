package com.fast.backend.station.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 측정 설비가 비면 대기 중인 측정 요청을 주기적으로 발행한다. */
@Component
public class StationMeasurementRequestDispatchScheduler {

    private final StationMeasurementRequestDispatchService dispatchService;

    public StationMeasurementRequestDispatchScheduler(
            StationMeasurementRequestDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(
            initialDelayString = "${station.measurement-request.dispatch-interval-ms:1000}",
            fixedDelayString = "${station.measurement-request.dispatch-interval-ms:1000}")
    public void dispatch() {
        dispatchService.dispatchIfStationAvailable();
    }
}
