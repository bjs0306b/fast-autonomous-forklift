package com.fast.backend.loadsafety.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.loadsafety.dto.LoadSafetyMessage;
import com.fast.backend.loadsafety.dto.LoadSafetyResponse;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 적재 화물 안전 상태 수신·저장·브로드캐스트 검증(prompt63.md 3장 1·2·3·7·9번).
 *
 * <p>이 테스트가 지키려는 핵심 계약은 <b>백엔드가 위험도를 재판정하지 않는다</b>는 것이다 — 비전이 보낸
 * riskLevel/riskCode/message가 저장·중계 과정에서 변형되면 화면과 실제 안전 로직의 기준이 갈라진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LoadSafetyServiceIntegrationTest {

    @Autowired private LoadSafetyService loadSafetyService;
    @Autowired private VehicleMapper vehicleMapper;

    @MockBean private VehicleWebSocketBroadcaster broadcaster;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    private static final OffsetDateTime DETECTED_AT =
            OffsetDateTime.of(2026, 7, 29, 10, 30, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void warningMessage_isStoredAndBroadcastVerbatim() {
        register("LS-F01");

        loadSafetyService.handleLoadSafety(message("LS-F01", "WARNING", "LOAD_TILT_EXCEEDED"));

        LoadSafetyResponse latest = loadSafetyService.getLatest("LS-F01");
        assertThat(latest).isNotNull();
        assertThat(latest.riskLevel()).isEqualTo("WARNING");
        assertThat(latest.riskCode()).isEqualTo("LOAD_TILT_EXCEEDED");
        assertThat(latest.message()).isEqualTo("화물이 좌측으로 과도하게 기울었습니다.");
        assertThat(latest.source()).isEqualTo("VISION");
        assertThat(latest.cargoId()).isEqualTo("CARGO-001");
        assertThat(latest.forkHeight()).isEqualTo(0.86);
        assertThat(latest.cargoHeight()).isEqualTo(1.42);
        assertThat(latest.roll()).isEqualTo(7.4);
        assertThat(latest.pitch()).isEqualTo(3.1);
        assertThat(latest.loadOffsetX()).isEqualTo(-0.18);
        assertThat(latest.loadOffsetY()).isEqualTo(0.04);
        assertThat(latest.detectedAt()).isEqualTo(DETECTED_AT);
        assertThat(latest.receivedAt()).isNotNull();
    }

    @Test
    void broadcast_usesDetectedAtAsOccurredAt() {
        register("LS-F02");

        loadSafetyService.handleLoadSafety(message("LS-F02", "DANGER", "LOAD_UNSTABLE"));

        ArgumentCaptor<LoadSafetyResponse> data = ArgumentCaptor.forClass(LoadSafetyResponse.class);
        ArgumentCaptor<OffsetDateTime> occurredAt = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(broadcaster).broadcastLoadSafety(eq("LS-F02"), data.capture(), occurredAt.capture());
        assertThat(data.getValue().riskLevel()).isEqualTo("DANGER");
        // 서버 처리 시각이 아니라 센서 감지 시각이어야 한다 — 화면이 데이터 신선도를 판단하는 근거다.
        assertThat(occurredAt.getValue()).isEqualTo(DETECTED_AT);
    }

    @Test
    void latestValueWins_onRepeatedMessages() {
        register("LS-F03");

        loadSafetyService.handleLoadSafety(message("LS-F03", "NORMAL", null));
        loadSafetyService.handleLoadSafety(message("LS-F03", "DANGER", "LOAD_UNSTABLE"));

        // 차량당 1행 upsert이므로 최신 값만 남는다(이력 테이블이 아니다).
        assertThat(loadSafetyService.getLatest("LS-F03").riskLevel()).isEqualTo("DANGER");
        assertThat(loadSafetyService.getAllLatest()).hasSize(1);
    }

    @Test
    void unregisteredVehicle_isIgnoredWithoutBroadcast() {
        // 차량을 등록하지 않는다(3장 9번 미등록 차량 이벤트 방어).
        loadSafetyService.handleLoadSafety(message("LS-GHOST", "DANGER", "LOAD_UNSTABLE"));

        verify(broadcaster, never()).broadcastLoadSafety(any(), any(), any());
    }

    @Test
    void unknownRiskLevel_isStoredAsUnknownButMeasurementsSurvive() {
        register("LS-F04");

        loadSafetyService.handleLoadSafety(message("LS-F04", "CRITICAL", "SOMETHING_NEW"));

        LoadSafetyResponse latest = loadSafetyService.getLatest("LS-F04");
        // 계약에 없는 단계라 UNKNOWN이지만, 메시지를 통째로 버리지는 않는다 — 측정값은 여전히 유효하다.
        assertThat(latest.riskLevel()).isEqualTo("UNKNOWN");
        assertThat(latest.riskCode()).isEqualTo("SOMETHING_NEW");
        assertThat(latest.roll()).isEqualTo(7.4);
    }

    @Test
    void missingRequiredFields_areRejected() {
        register("LS-F05");

        loadSafetyService.handleLoadSafety(new LoadSafetyMessage(
                "LS-F05", null, null, null, null, null, null, null, null, null, null, null, DETECTED_AT));
        loadSafetyService.handleLoadSafety(new LoadSafetyMessage(
                "LS-F05", null, null, null, null, null, null, null, "WARNING", null, null, null, null));
        loadSafetyService.handleLoadSafety(new LoadSafetyMessage(
                null, null, null, null, null, null, null, null, "WARNING", null, null, null, DETECTED_AT));

        // riskLevel 없음 / detectedAt 없음 / vehicleId 없음 — 셋 다 저장도 브로드캐스트도 하지 않는다.
        assertThat(loadSafetyService.getLatest("LS-F05")).isNull();
        verify(broadcaster, never()).broadcastLoadSafety(any(), any(), any());
    }

    @Test
    void neverReceived_returnsNullNotException() {
        register("LS-F06");

        // 미수신은 오류가 아니다 — /location/latest와 동일하게 data:null로 내려간다.
        assertThat(loadSafetyService.getLatest("LS-F06")).isNull();
    }

    @Test
    void unregisteredVehicleQuery_is404() {
        assertThatThrownBy(() -> loadSafetyService.getLatest("LS-NOPE"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void optionalMeasurements_mayBeNull() {
        register("LS-F07");

        // IMU가 없는 차량이 "위험 아님"만 보고하는 경우. 없는 값을 0으로 위조하지 않는다.
        loadSafetyService.handleLoadSafety(new LoadSafetyMessage(
                "LS-F07", null, null, null, null, null, null, null, "NORMAL", null, null, "SENSOR", DETECTED_AT));

        LoadSafetyResponse latest = loadSafetyService.getLatest("LS-F07");
        assertThat(latest.riskLevel()).isEqualTo("NORMAL");
        assertThat(latest.roll()).isNull();
        assertThat(latest.pitch()).isNull();
        assertThat(latest.cargoHeight()).isNull();
        assertThat(latest.cargoId()).isNull();
    }

    private LoadSafetyMessage message(String vehicleId, String riskLevel, String riskCode) {
        return new LoadSafetyMessage(
                vehicleId, "CARGO-001", 0.86, 1.42, 7.4, 3.1, -0.18, 0.04,
                riskLevel, riskCode, "화물이 좌측으로 과도하게 기울었습니다.", "VISION", DETECTED_AT);
    }

    private void register(String vehicleId) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(VehicleSource.REAL);
        v.setActive(true);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);
    }
}
