package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementMqttFlowIntegrationTest {

    @Autowired private StationMeasurementService service;
    @Autowired private CargoMapper cargoMapper;

    @Test
    void acceptedResult_isStoredAndReleasesTheSingleStation() {
        Long cargoId = insertCargo();
        StationSession session = service.openSession(cargoId);

        StationMeasurementResponse response = service.create(new StationMeasurementCreateRequest(
                session.getSessionId(),
                "MEASUREMENT-MQTT-FLOW",
                "ok",
                0.50,
                null,   // cargoWidth — 스테이션 미전송 경로
                "safe",
                0.02));

        assertThat(response.sessionId()).isEqualTo(session.getSessionId());
        assertThat(response.cargoId()).isEqualTo(cargoId);
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
        StationSession session = service.openSession(insertCargo());

        assertThatThrownBy(() -> service.closeSession(session.getSessionId()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STATION_MEASUREMENT_NOT_COMPLETED);
    }

    @Test
    void unknownCargo_cannotOpenMeasurementSession() {
        assertThatThrownBy(() -> service.openSession(Long.MAX_VALUE))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CARGO_NOT_FOUND);
    }

    private Long insertCargo() {
        Cargo cargo = new Cargo();
        cargo.setCreatedAt(LocalDateTime.now());
        cargoMapper.insert(cargo);
        return cargo.getCargoId();
    }
}
