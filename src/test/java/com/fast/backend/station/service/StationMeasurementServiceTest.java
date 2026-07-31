package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationMeasurementResponseMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST 저장 경로({@code StationMeasurementService#create})의 검증·정규화·세션 연결 규칙(prompt95.md
 * 14·15·16장).
 *
 * <p>MQTT 시절의 {@code process()} 테스트를 대체한다. 가장 큰 행동 차이를 여기서 고정한다 —
 * <b>검증 실패가 더 이상 조용히 삼켜지지 않는다</b>. 옛 경로는 발행자에게 응답할 수 없어 로그만 남기고
 * 정상 종료했지만, REST는 호출자가 결과를 알아야 하므로 예외가 그대로 올라가야 한다.
 */
class StationMeasurementServiceTest {

    private static final String ACTIVE_SESSION_ID = "session-active-1";
    private static final String CARGO_ID = "CARGO-001";
    private static final OffsetDateTime MEASURED_AT = OffsetDateTime.parse("2026-07-31T09:37:48+09:00");

    private StationMeasurementMapper measurementMapper;
    private StationSessionMapper sessionMapper;
    private StationMeasurementBroadcaster broadcaster;
    private StationMeasurementService service;

    @BeforeEach
    void setUp() {
        measurementMapper = mock(StationMeasurementMapper.class);
        sessionMapper = mock(StationSessionMapper.class);
        broadcaster = mock(StationMeasurementBroadcaster.class);
        service = new StationMeasurementService(measurementMapper, sessionMapper,
                new StationMeasurementResponseMapper(),
                new StationMeasurementPlacementEligibility(
                        new com.fast.backend.storage.placement.PlacementProperties(0.05, 0.12, 0.05)),
                broadcaster,
                new com.fast.backend.station.config.StationSessionProperties(600L),
                java.time.Clock.systemDefaultZone());

        when(measurementMapper.existsByMeasurementId(any())).thenReturn(false);
        when(measurementMapper.existsBySessionId(any())).thenReturn(false);
        when(sessionMapper.findActiveSession())
                .thenReturn(Optional.of(new StationSession(ACTIVE_SESSION_ID, CARGO_ID)));
        // create() 는 잠금 조회로 활성 세션을 확인한 뒤 세션 행을 읽는다(prompt107).
        StationState occupied = new StationState();
        occupied.setActiveSessionId(ACTIVE_SESSION_ID);
        occupied.setAcquiredAt(java.time.LocalDateTime.now());
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(occupied));
        when(sessionMapper.findBySessionId(ACTIVE_SESSION_ID))
                .thenReturn(Optional.of(new StationSession(ACTIVE_SESSION_ID, CARGO_ID)));
    }

    // ── 정상 저장 ───────────────────────────────────────────────────────────

    @Test
    void create_storesMeterHeightAndUppercaseTippingLevel_underActiveSession() {
        StationMeasurementResponse response = service.create(
                new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-001", "ok", 0.723, "safe", 0.057, MEASURED_AT));

        StationMeasurement saved = captureInserted();
        // 요청이 sessionId 를 담지 않았는데도 활성 세션이 붙어야 한다(prompt95.md 7장).
        assertThat(saved.getSessionId()).isEqualTo(ACTIVE_SESSION_ID);
        assertThat(saved.getStatus()).isEqualTo(StationMeasurementStatus.OK);
        // meter 값이 그대로 저장된다 — cm 로 되돌리는 자동 변환이 없어야 한다.
        assertThat(saved.getCargoHeight()).isEqualTo(0.723);
        // 비전은 소문자로 보내지만 DB CHECK 는 대문자만 허용한다.
        assertThat(saved.getTippingLevel()).isEqualTo("SAFE");
        assertThat(saved.getOverhangRatio()).isEqualTo(0.057);
        assertThat(saved.getCreatedAt()).isNotNull();

        assertThat(response.measurementId()).isEqualTo("M-001");
        assertThat(response.cargoId()).isEqualTo(CARGO_ID);
        assertThat(response.tippingLevel()).isEqualTo("SAFE");
        verify(broadcaster, times(1)).broadcast(any(), any());
    }

    @Test
    void create_acceptsAlreadyUppercaseTippingLevel() {
        service.create(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-002", "ok", 1.2, "DANGER", 0.4, MEASURED_AT));

        assertThat(captureInserted().getTippingLevel()).isEqualTo("DANGER");
    }

    @Test
    void create_storesDimensionsOnly_withHeightButNullRiskValues() {
        service.create(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M-003", "dimensions_only", 0.723, null, null, MEASURED_AT));

        StationMeasurement saved = captureInserted();
        assertThat(saved.getStatus()).isEqualTo(StationMeasurementStatus.DIMENSIONS_ONLY);
        // 팔레트를 못 찾아 판정이 불가능한 상태다 — 높이는 남기고 위험값은 지어내지 않는다.
        // 특히 화물 높이에 팔레트 높이를 더해 저장하지 않는다(그 덧셈은 PlacementService 몫이다).
        assertThat(saved.getCargoHeight()).isEqualTo(0.723);
        assertThat(saved.getTippingLevel()).isNull();
        assertThat(saved.getOverhangRatio()).isNull();
    }

    @Test
    void create_storesNoDetectionAndUnreliable_withAllMeasurementsNull() {
        service.create(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-004", "no_detection", null, null, null, MEASURED_AT));
        service.create(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-005", "unreliable", null, null, null, MEASURED_AT));

        ArgumentCaptor<StationMeasurement> captor = ArgumentCaptor.forClass(StationMeasurement.class);
        verify(measurementMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(saved -> {
            assertThat(saved.getCargoHeight()).isNull();
            assertThat(saved.getTippingLevel()).isNull();
            assertThat(saved.getOverhangRatio()).isNull();
        });
        assertThat(captor.getAllValues().get(0).getStatus()).isEqualTo(StationMeasurementStatus.NO_DETECTION);
        assertThat(captor.getAllValues().get(1).getStatus()).isEqualTo(StationMeasurementStatus.UNRELIABLE);
    }

    // ── 세션·중복 ───────────────────────────────────────────────────────────

    @Test
    void create_rejectsWhenNoActiveSession_withoutCreatingOne() {
        // 잠금 조회가 유휴를 돌려준다(prompt107 이후 create() 는 이 경로로 판정한다).
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(new StationState()));

        assertThatThrownBy(() -> service.create(
                new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-006", "ok", 0.723, "safe", 0.0, MEASURED_AT)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);

        // 세션이 없다고 해서 세션을 만들어 붙이지 않는다.
        verify(sessionMapper, never()).insert(any());
        assertNothingStored();
    }

    @Test
    void create_rejectsDuplicateMeasurementId_withoutOverwritingOrRebroadcasting() {
        when(measurementMapper.existsByMeasurementId("M-DUP")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-DUP", "ok", 0.723, "safe", 0.0, MEASURED_AT)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_MEASUREMENT_ID_DUPLICATED);

        assertNothingStored();
    }

    // ── 거부되는 요청 ───────────────────────────────────────────────────────

    @Test
    void create_storesCentimeterLookingHeightVerbatim_neverAutoConvertingUnits() {
        // 72.3 은 cm 로 재던 값이 그대로 넘어온 전형적인 실수다. 백엔드가 100으로 나눠 "고쳐 주면"
        // 진짜로 7230cm 짜리 화물을 잰 경우와 구분할 수 없다 — 저장은 하되 변환은 하지 않는다.
        service.create(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-CM", "ok", 72.3, "safe", 0.0, MEASURED_AT));

        assertThat(captureInserted().getCargoHeight()).isEqualTo(72.3);
    }

    @Test
    void create_rejectsInvalidCargoHeight() {
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-H1", "ok", 0.0, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-H2", "ok", -0.5, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-H3", "ok", Double.NaN, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M-H4", "ok", Double.POSITIVE_INFINITY, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-H5", "ok", null, "safe", 0.0, MEASURED_AT));
    }

    @Test
    void create_rejectsUnknownTippingLevel_ratherThanNullingIt() {
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-T1", "ok", 0.723, "critical", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-T2", "ok", 0.723, "", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-T3", "ok", 0.723, null, 0.0, MEASURED_AT));
    }

    @Test
    void create_rejectsInvalidOverhangRatio_butAllowsAboveOne() {
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-O1", "ok", 0.723, "safe", -0.01, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-O2", "ok", 0.723, "safe", Double.NaN, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-O3", "ok", 0.723, "safe", null, MEASURED_AT));

        // 화물이 파렛트보다 넓으면 1.0 을 넘을 수 있다 — 문서에 상한이 없으므로 만들어 넣지 않는다.
        service.create(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-O4", "ok", 0.723, "danger", 1.4, MEASURED_AT));
        assertThat(captureInserted().getOverhangRatio()).isEqualTo(1.4);
    }

    @Test
    void create_rejectsRiskValuesOnStatusesThatCannotHaveThem() {
        // 판정할 수 없는 상태인데 판정값이 딸려 오면 데스크탑이 잘못 보내고 있다는 뜻이다 — 무시하면
        // 아무도 모른다.
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M-D1", "dimensions_only", 0.723, "safe", null, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M-D2", "dimensions_only", 0.723, null, 0.05, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M-D3", "no_detection", 0.723, null, null, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M-D4", "unreliable", null, "safe", null, MEASURED_AT));
    }

    @Test
    void create_rejectsMissingOrOversizedMeasurementId() {
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, null, "ok", 0.723, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "  ", "ok", 0.723, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, 
                "M".repeat(101), "ok", 0.723, "safe", 0.0, MEASURED_AT));
    }

    @Test
    void create_rejectsUnknownStatus() {
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-S1", "partial", 0.723, "safe", 0.0, MEASURED_AT));
        assertRejected(new StationMeasurementCreateRequest(ACTIVE_SESSION_ID, "M-S2", null, 0.723, "safe", 0.0, MEASURED_AT));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private StationMeasurement captureInserted() {
        ArgumentCaptor<StationMeasurement> captor = ArgumentCaptor.forClass(StationMeasurement.class);
        verify(measurementMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 검증 실패는 예외로 드러나야 하고, 저장·브로드캐스트가 일어나면 안 된다. */
    private void assertRejected(StationMeasurementCreateRequest request) {
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(BusinessException.class);
        assertNothingStored();
    }

    private void assertNothingStored() {
        verify(measurementMapper, never()).insert(any());
        verify(broadcaster, never()).broadcast(any(), any());
    }
}
