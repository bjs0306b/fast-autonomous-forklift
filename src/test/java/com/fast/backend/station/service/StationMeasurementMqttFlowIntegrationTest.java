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

        // AI는 finally에서 기존 DELETE API를 한 번 더 호출한다. 자동 해제된 세션이므로
        // SESSION_NOT_ACTIVE가 반환되고 AI는 이를 이미 닫힌 정상 상태로 처리한다.
        assertThatThrownBy(() -> service.closeSession(session.getSessionId()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);
    }

    @Test
    void incompleteActiveSession_isNotClosedBeforeTtl() {
        StationSession session = service.openSession("CARGO-INCOMPLETE");

        assertThatThrownBy(() -> service.closeSession(session.getSessionId()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STATION_MEASUREMENT_NOT_COMPLETED);
    }
}
