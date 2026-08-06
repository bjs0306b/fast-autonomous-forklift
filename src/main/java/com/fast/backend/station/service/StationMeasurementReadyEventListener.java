package com.fast.backend.station.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** MOVE 성공 상태가 커밋된 뒤 측정 설비가 비었는지 즉시 확인한다. */
@Component
public class StationMeasurementReadyEventListener {

    private final StationMeasurementRequestDispatchService dispatchService;

    public StationMeasurementReadyEventListener(
            StationMeasurementRequestDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(StationMeasurementReadyEvent event) {
        dispatchService.dispatchIfStationAvailable();
    }
}
