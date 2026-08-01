package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementMqttFlowIntegrationTest {

    @Autowired private StationMeasurementService service;

    @Test
    void acceptedResult_isStoredAndReleasesTheSingleStation() {
        StationSession session = service.openSession("CARGO-MQTT-FLOW");

        StationMeasurementResponse response = service.create(new StationMeasurementCreateRequest(
                session.getSessionId(),
                "MEASUREMENT-MQTT-FLOW",
                "ok",
                0.50,
                "safe",
                0.02));

        assertThat(response.sessionId()).isEqualTo(session.getSessionId());
        assertThat(response.cargoId()).isEqualTo("CARGO-MQTT-FLOW");
        assertThat(service.findByMeasurementId("MEASUREMENT-MQTT-FLOW").cargoHeight())
                .isEqualTo(0.50);
        assertThatThrownBy(service::findActiveSession)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);
    }
}
