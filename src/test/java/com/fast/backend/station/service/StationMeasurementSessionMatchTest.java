package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.config.StationSessionProperties;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationMeasurementResponseMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import com.fast.backend.storage.placement.PlacementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 측정 요청의 {@code sessionId} 와 활성 세션 대조(prompt107).
 *
 * <p>막으려는 것은 하나다 — <b>세션 A 가 해제되고 세션 B 가 열린 뒤 도착한 A 의 측정이 B 에
 * 저장되는 것.</b> 예전에는 요청에 세션 정보가 없어 백엔드가 활성 세션에 무조건 귀속시켰다.
 *
 * <p>TOCTOU 방지(검증과 INSERT 사이에 세션이 바뀌는 경합)는 {@code findStateForUpdate()} 의 행
 * 잠금으로 처리하므로, 여기서는 <b>잠금 조회 결과로 판정하는지</b>와 <b>저장되는 sessionId 의
 * 출처</b>를 고정한다. 실제 잠금 동작은 통합 테스트가 본다.
 */
class StationMeasurementSessionMatchTest {

    private static final String SESSION_A = "session-A";
    private static final String SESSION_B = "session-B";
    private static final String CARGO = "CARGO-1";
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
                new StationMeasurementPlacementEligibility(new PlacementProperties(0.05, 0.12, 0.05)),
                broadcaster,
                new StationSessionProperties(600L),
                Clock.systemDefaultZone());

        when(measurementMapper.existsByMeasurementId(any())).thenReturn(false);
        when(measurementMapper.existsBySessionId(any())).thenReturn(false);
    }

    private void occupiedBy(String sessionId) {
        StationState state = new StationState();
        state.setActiveSessionId(sessionId);
        state.setAcquiredAt(LocalDateTime.now());
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(state));
        when(sessionMapper.findBySessionId(sessionId))
                .thenReturn(Optional.of(new StationSession(sessionId, CARGO)));
    }

    private void idle() {
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(new StationState()));
    }

    private StationMeasurementCreateRequest request(String sessionId, String measurementId) {
        return new StationMeasurementCreateRequest(
                sessionId, measurementId, "ok", 0.723, "safe", 0.01, MEASURED_AT);
    }

    private void assertNothingStored() {
        verify(measurementMapper, never()).insert(any());
        verify(broadcaster, never()).broadcast(any(), any());
    }

    // ── sessionId 필수 ──────────────────────────────────────────────────────

    @Test
    void create_rejectsMissingSessionId() {
        assertThatThrownBy(() -> service.create(request(null, "M-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        assertNothingStored();
    }

    @Test
    void create_rejectsBlankSessionId() {
        assertThatThrownBy(() -> service.create(request("   ", "M-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        assertNothingStored();
    }

    @Test
    void create_rejectsOversizedSessionId() {
        assertThatThrownBy(() -> service.create(request("S".repeat(101), "M-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        assertNothingStored();
    }

    // ── 활성 세션 대조 ──────────────────────────────────────────────────────

    @Test
    void create_rejectsWhenNoActiveSession() {
        idle();

        assertThatThrownBy(() -> service.create(request(SESSION_A, "M-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);
        assertNothingStored();
    }

    @Test
    void create_rejectsWhenRequestedSessionIsNotTheActiveOne() {
        // 세션 A 는 해제됐고 지금은 B 가 점유 중. A 의 늦은 측정이 도착했다.
        occupiedBy(SESSION_B);

        assertThatThrownBy(() -> service.create(request(SESSION_A, "M-LATE")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_MISMATCH);
        assertNothingStored();
    }

    @Test
    void lateMeasurementIsNeverReattributedToTheNewSession() {
        occupiedBy(SESSION_B);

        assertThatThrownBy(() -> service.create(request(SESSION_A, "M-LATE")))
                .isInstanceOf(BusinessException.class);

        // 활성 세션(B)으로 바꿔치기해 저장하지 않는다 — 이 테스트가 이번 변경의 핵심이다.
        verify(measurementMapper, never()).insert(any());
    }

    @Test
    void create_succeedsWhenSessionMatches() {
        occupiedBy(SESSION_A);

        service.create(request(SESSION_A, "M-1"));

        verify(measurementMapper).insert(any());
        verify(broadcaster).broadcast(any(), any());
    }

    @Test
    void storedSessionIdComesFromTheRequest() {
        occupiedBy(SESSION_A);

        service.create(request(SESSION_A, "M-1"));

        ArgumentCaptor<StationMeasurement> captor = ArgumentCaptor.forClass(StationMeasurement.class);
        verify(measurementMapper).insert(captor.capture());
        assertThat(captor.getValue().getSessionId()).isEqualTo(SESSION_A);
    }

    @Test
    void create_usesLockingReadNotPlainActiveSessionLookup() {
        occupiedBy(SESSION_A);

        service.create(request(SESSION_A, "M-1"));

        // 잠금 없는 findActiveSession() 으로 판정하면 검증과 INSERT 사이에 세션이 바뀔 수 있다.
        verify(sessionMapper).findStateForUpdate();
        verify(sessionMapper, never()).findActiveSession();
    }

    @Test
    void create_rejectsWhenSessionRowIsMissing() {
        // state 는 점유를 가리키는데 세션 행이 없다면 데이터가 깨진 상태다 — 저장하지 않는다.
        StationState state = new StationState();
        state.setActiveSessionId(SESSION_A);
        state.setAcquiredAt(LocalDateTime.now());
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(state));
        when(sessionMapper.findBySessionId(SESSION_A)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(SESSION_A, "M-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_NOT_FOUND);
        assertNothingStored();
    }

    // ── 기존 정책 회귀 ──────────────────────────────────────────────────────

    @Test
    void duplicateMeasurementId_stillRejectedBeforeSessionCheck() {
        occupiedBy(SESSION_A);
        when(measurementMapper.existsByMeasurementId("M-DUP")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(SESSION_A, "M-DUP")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_MEASUREMENT_ID_DUPLICATED);
        assertNothingStored();
    }

    @Test
    void secondMeasurementInSameSession_stillRejected() {
        occupiedBy(SESSION_A);
        when(measurementMapper.existsBySessionId(SESSION_A)).thenReturn(true);

        assertThatThrownBy(() -> service.create(request(SESSION_A, "M-2")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_MEASUREMENT_ALREADY_EXISTS);
        assertNothingStored();
    }
}
