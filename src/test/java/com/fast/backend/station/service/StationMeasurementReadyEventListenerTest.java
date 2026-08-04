package com.fast.backend.station.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StationMeasurementReadyEventListenerTest {

    @Test
    void readyEvent_dispatchesMeasurementRequestAfterCommit() {
        StationMeasurementRequestDispatchService dispatchService =
                mock(StationMeasurementRequestDispatchService.class);
        StationMeasurementReadyEventListener listener =
                new StationMeasurementReadyEventListener(dispatchService);

        listener.handle(new StationMeasurementReadyEvent(7L));

        verify(dispatchService).dispatchIfStationAvailable();
    }
}
