package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.config.StationSessionProperties;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationMeasurementResponseMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import com.fast.backend.storage.placement.PlacementProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 측정 세션 TTL 자동 해제와 운영자 강제 해제(prompt106).
 *
 * <p><b>시간을 실제로 기다리지 않는다.</b> {@link Clock#fixed} 로 "지금"을 고정해 만료/미만료를
 * 즉시 재현한다 — TTL 이 10분이라고 {@code Thread.sleep(10분)} 을 하면 테스트가 쓸모없어진다.
 *
 * <p>만료 판정 자체는 <b>SQL 의 조건부 UPDATE 가 단독으로</b> 한다(동시성 때문에). 그래서 여기서는
 * Service 가 Mapper 에 <b>어떤 기준 시각을 넘기는지</b>와, 그 결과를 어떻게 해석하는지를 고정한다.
 * SQL 조건이 실제 DB 에서 동작하는지는 {@code StationSessionLifecycleIntegrationTest} 가 본다.
 */
class StationSessionTtlTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-31T03:00:00Z");
    private static final long TTL_SECONDS = 600L;

    private StationMeasurementMapper measurementMapper;
    private StationSessionMapper sessionMapper;
    private StationMeasurementBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        measurementMapper = mock(StationMeasurementMapper.class);
        sessionMapper = mock(StationSessionMapper.class);
        broadcaster = mock(StationMeasurementBroadcaster.class);
    }

    private StationMeasurementService serviceWith(long ttlSeconds) {
        return new StationMeasurementService(measurementMapper, sessionMapper,
                new StationMeasurementResponseMapper(),
                new StationMeasurementPlacementEligibility(new PlacementProperties(0.05, 0.12, 0.05)),
                broadcaster,
                new StationSessionProperties(ttlSeconds),
                Clock.fixed(NOW, ZONE));
    }

    private StationMeasurementService service() {
        return serviceWith(TTL_SECONDS);
    }

    /** 고정된 "지금" 시각(서비스가 보는 값과 같다). */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(NOW, ZONE);
    }

    private StationState state(String sessionId, LocalDateTime acquiredAt) {
        StationState s = new StationState();
        s.setActiveSessionId(sessionId);
        s.setAcquiredAt(acquiredAt);
        return s;
    }

    // ── openSession / TTL ───────────────────────────────────────────────────

    @Test
    void openSession_succeedsWhenIdle() {
        when(sessionMapper.findState()).thenReturn(Optional.of(state(null, null)));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(1);

        StationSession opened = service().openSession("CARGO-1");

        assertThat(opened.getSessionId()).isNotBlank();
        assertThat(opened.getCargoId()).isEqualTo("CARGO-1");
        verify(sessionMapper).insert(any());
    }

    @Test
    void openSession_passesNowAndExpiryThresholdToMapper() {
        when(sessionMapper.findState()).thenReturn(Optional.of(state(null, null)));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(1);

        service().openSession("CARGO-1");

        ArgumentCaptor<LocalDateTime> acquiredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> expiredBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).acquireStation(anyString(), acquiredAt.capture(), expiredBefore.capture());

        // acquired_at 은 "지금", 만료 기준은 "지금 - TTL" 이어야 한다.
        assertThat(acquiredAt.getValue()).isEqualTo(now());
        assertThat(expiredBefore.getValue()).isEqualTo(now().minusSeconds(TTL_SECONDS));
    }

    @Test
    void openSession_rejectsWhenOccupiedAndNotExpired() {
        // TTL 미경과 세션이 점유 중 — SQL 조건이 안 맞아 0행이 반영된다.
        when(sessionMapper.findState())
                .thenReturn(Optional.of(state("old-session", now().minusSeconds(60))));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service().openSession("CARGO-2"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_ALREADY_OCCUPIED);
    }

    @Test
    void openSession_reclaimsExpiredSession() {
        // TTL 경과 세션 — SQL 이 회수에 성공(1행)한다.
        when(sessionMapper.findState())
                .thenReturn(Optional.of(state("dead-session", now().minusSeconds(TTL_SECONDS + 1))));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(1);

        StationSession opened = service().openSession("CARGO-2");

        assertThat(opened.getSessionId()).isNotEqualTo("dead-session");
        // 새 점유 시각이 "지금"으로 기록돼야 한다(만료된 옛 시각이 남으면 즉시 또 만료된다).
        ArgumentCaptor<LocalDateTime> acquiredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).acquireStation(eq(opened.getSessionId()), acquiredAt.capture(), any());
        assertThat(acquiredAt.getValue()).isEqualTo(now());
    }

    @Test
    void openSession_reclaimsSessionWithNullAcquiredAt() {
        // acquired_at 이 없던 시절의 행. "언제부터 점유됐는지 모름" → 만료로 본다.
        when(sessionMapper.findState()).thenReturn(Optional.of(state("legacy-session", null)));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(1);

        assertThatCode(() -> service().openSession("CARGO-2")).doesNotThrowAnyException();
    }

    @Test
    void openSession_reclaimsSessionWithoutAnyMeasurement() {
        // 측정 행이 하나도 없는 세션도 회수 대상이다 — 측정 전에 죽은 경우가 바로 이 기능의 대상이다.
        when(sessionMapper.findState())
                .thenReturn(Optional.of(state("dead-session", now().minusSeconds(TTL_SECONDS + 1))));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(1);

        assertThatCode(() -> service().openSession("CARGO-2")).doesNotThrowAnyException();
        // 회수 과정에서 측정 행을 만들지 않는다.
        verify(measurementMapper, never()).insert(any());
    }

    @Test
    void ttlIsConfigurable_notHardcoded() {
        when(sessionMapper.findState()).thenReturn(Optional.of(state(null, null)));
        when(sessionMapper.acquireStation(anyString(), any(), any())).thenReturn(1);

        serviceWith(30L).openSession("CARGO-1");

        ArgumentCaptor<LocalDateTime> expiredBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sessionMapper).acquireStation(anyString(), any(), expiredBefore.capture());
        // 설정을 30초로 바꾸면 기준 시각도 30초로 따라와야 한다(600 이 코드에 박혀 있지 않다는 증거).
        assertThat(expiredBefore.getValue()).isEqualTo(now().minusSeconds(30));
    }

    @Test
    void openSession_rejectsBlankCargoId() {
        assertThatThrownBy(() -> service().openSession("  "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(sessionMapper, never()).acquireStation(anyString(), any(), any());
    }

    // ── 강제 해제 ───────────────────────────────────────────────────────────

    @Test
    void forceRelease_releasesWithoutAnyMeasurement() {
        when(sessionMapper.findState())
                .thenReturn(Optional.of(state("stuck-session", now().minusSeconds(30))));
        when(sessionMapper.forceReleaseStation("stuck-session")).thenReturn(1);

        assertThatCode(() -> service().forceReleaseSession("stuck-session")).doesNotThrowAnyException();

        verify(sessionMapper).forceReleaseStation("stuck-session");
    }

    @Test
    void forceRelease_doesNotInsertFakeMeasurement() {
        when(sessionMapper.findState())
                .thenReturn(Optional.of(state("stuck-session", now().minusSeconds(30))));
        when(sessionMapper.forceReleaseStation("stuck-session")).thenReturn(1);

        service().forceReleaseSession("stuck-session");

        // 기존 우회책(desktop --abandon)은 unreliable 행을 남겼다. 서버 경로는 그러지 않는다.
        verify(measurementMapper, never()).insert(any());
        verify(broadcaster, never()).broadcast(any(), any());
    }

    @Test
    void forceRelease_rejectsMismatchedSessionId() {
        when(sessionMapper.findState())
                .thenReturn(Optional.of(state("current-session", now().minusSeconds(30))));
        when(sessionMapper.forceReleaseStation("other-session")).thenReturn(0);

        assertThatThrownBy(() -> service().forceReleaseSession("other-session"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);
    }

    @Test
    void forceRelease_onAlreadyIdleStation_isRejectedNotSilentlyIgnored() {
        // idempotent 204 가 아니라 409 다 — 기존 closeSession 과 같은 정책(응답으로 상태를 알려준다).
        when(sessionMapper.findState()).thenReturn(Optional.of(state(null, null)));
        when(sessionMapper.forceReleaseStation(anyString())).thenReturn(0);

        assertThatThrownBy(() -> service().forceReleaseSession("any-session"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);
    }

    @Test
    void forceRelease_rejectsBlankSessionId() {
        assertThatThrownBy(() -> service().forceReleaseSession(" "))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_REQUEST);
        verify(sessionMapper, never()).forceReleaseStation(anyString());
    }

    // ── 늦게 도착한 측정 ────────────────────────────────────────────────────

    @Test
    void lateMeasurement_isRejectedAfterSessionReleased() {
        // TTL/강제 해제로 세션이 풀린 뒤 도착한 측정. 활성 세션이 없으므로 저장하지 않는다.
        when(measurementMapper.existsByMeasurementId(any())).thenReturn(false);
        // 세션이 이미 풀린 상태 — 잠금 조회가 유휴를 돌려준다.
        when(sessionMapper.findStateForUpdate()).thenReturn(Optional.of(state(null, null)));

        assertThatThrownBy(() -> service().create(
                new com.fast.backend.station.dto.StationMeasurementCreateRequest(
                        "s-gone", "M-LATE", "ok", 0.723, "safe", 0.01, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);

        verify(measurementMapper, never()).insert(any());
        verify(broadcaster, never()).broadcast(any(), any());
    }
}
