package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL 점유·강제 해제 SQL 이 실제 DB(H2 MySQL 호환 모드)에서 동작하는지 검증한다(prompt106).
 *
 * <p>단위 테스트는 Service 가 <b>어떤 기준 시각을 넘기는지</b>만 고정할 수 있다. 만료 판정 자체는
 * SQL 의 WHERE 절이 하므로, 그 조건이 정말 맞는지는 여기서 실제 UPDATE 를 돌려 확인해야 한다 —
 * 조건을 잘못 쓰면 만료되지 않은 세션을 빼앗거나(데이터 유실) 만료된 세션을 영영 못 푼다.
 *
 * <p>{@code acquired_at} 은 애플리케이션이 넘긴 값을 그대로 쓰므로, 시간을 기다리지 않고 "과거에
 * 점유된 것처럼" 값을 넣어 만료 상황을 만든다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationSessionTtlMapperTest {

    @Autowired private StationSessionMapper sessionMapper;
    @Autowired private CargoMapper cargoMapper;

    private static final long TTL_SECONDS = 600L;

    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now().withNano(0);
        insertCargo("TTL-CARGO-1");
        insertCargo("TTL-CARGO-2");
        // 다른 테스트가 남긴 점유가 있으면 초기 상태를 맞춘다(@Transactional 로 롤백되지만 방어적).
        sessionMapper.findState().filter(StationState::isOccupied)
                .ifPresent(s -> sessionMapper.forceReleaseStation(s.getActiveSessionId()));
    }

    private LocalDateTime expiredBefore() {
        return now.minusSeconds(TTL_SECONDS);
    }

    // ── 점유 ────────────────────────────────────────────────────────────────

    @Test
    void acquire_succeedsWhenIdle_andRecordsAcquiredAt() {
        openSessionRow("s-1", "TTL-CARGO-1");

        assertThat(sessionMapper.acquireStation("s-1", now, expiredBefore())).isEqualTo(1);

        StationState state = sessionMapper.findState().orElseThrow();
        assertThat(state.getActiveSessionId()).isEqualTo("s-1");
        assertThat(state.getAcquiredAt()).isEqualTo(now);
    }

    @Test
    void acquire_failsWhenOccupiedAndNotExpired() {
        openSessionRow("s-1", "TTL-CARGO-1");
        openSessionRow("s-2", "TTL-CARGO-2");
        // 1분 전에 점유 — TTL(10분) 미경과
        sessionMapper.acquireStation("s-1", now.minusSeconds(60), expiredBefore());

        assertThat(sessionMapper.acquireStation("s-2", now, expiredBefore())).isEqualTo(0);

        // 원래 점유가 그대로 유지돼야 한다.
        StationState state = sessionMapper.findState().orElseThrow();
        assertThat(state.getActiveSessionId()).isEqualTo("s-1");
        assertThat(state.getAcquiredAt()).isEqualTo(now.minusSeconds(60));
    }

    @Test
    void acquire_reclaimsExpiredSession_andReplacesAcquiredAt() {
        openSessionRow("s-1", "TTL-CARGO-1");
        openSessionRow("s-2", "TTL-CARGO-2");
        LocalDateTime longAgo = now.minusSeconds(TTL_SECONDS + 1);
        sessionMapper.acquireStation("s-1", longAgo, expiredBefore());

        assertThat(sessionMapper.acquireStation("s-2", now, expiredBefore())).isEqualTo(1);

        StationState state = sessionMapper.findState().orElseThrow();
        assertThat(state.getActiveSessionId()).isEqualTo("s-2");
        assertThat(state.getAcquiredAt()).isEqualTo(now);
    }

    @Test
    void acquire_treatsExactTtlBoundaryAsExpired() {
        openSessionRow("s-1", "TTL-CARGO-1");
        openSessionRow("s-2", "TTL-CARGO-2");
        // 정확히 TTL 만큼 지난 시점 — SQL 이 <= 이므로 만료다.
        sessionMapper.acquireStation("s-1", expiredBefore(), expiredBefore());

        assertThat(sessionMapper.acquireStation("s-2", now, expiredBefore())).isEqualTo(1);
    }

    @Test
    void acquire_onlyOneOfTwoConcurrentCallersWins() {
        openSessionRow("s-1", "TTL-CARGO-1");
        openSessionRow("s-2", "TTL-CARGO-2");

        int first = sessionMapper.acquireStation("s-1", now, expiredBefore());
        int second = sessionMapper.acquireStation("s-2", now, expiredBefore());

        // 두 번째는 조건이 맞지 않아 0행이다 — 단일 설비 뮤텍스가 유지된다.
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(0);
        assertThat(sessionMapper.findState().orElseThrow().getActiveSessionId()).isEqualTo("s-1");
    }

    // ── 강제 해제 ───────────────────────────────────────────────────────────

    @Test
    void forceRelease_clearsBothColumns_withoutMeasurement() {
        openSessionRow("s-1", "TTL-CARGO-1");
        sessionMapper.acquireStation("s-1", now, expiredBefore());

        assertThat(sessionMapper.forceReleaseStation("s-1")).isEqualTo(1);

        StationState state = sessionMapper.findState().orElseThrow();
        assertThat(state.getActiveSessionId()).isNull();
        assertThat(state.getAcquiredAt()).isNull();
        assertThat(state.isOccupied()).isFalse();
    }

    @Test
    void forceRelease_doesNothingWhenSessionIdMismatches() {
        openSessionRow("s-1", "TTL-CARGO-1");
        sessionMapper.acquireStation("s-1", now, expiredBefore());

        assertThat(sessionMapper.forceReleaseStation("other")).isEqualTo(0);
        assertThat(sessionMapper.findState().orElseThrow().getActiveSessionId()).isEqualTo("s-1");
    }

    @Test
    void forceRelease_thenNewSessionCanAcquire() {
        openSessionRow("s-1", "TTL-CARGO-1");
        openSessionRow("s-2", "TTL-CARGO-2");
        sessionMapper.acquireStation("s-1", now, expiredBefore());
        sessionMapper.forceReleaseStation("s-1");

        assertThat(sessionMapper.acquireStation("s-2", now, expiredBefore())).isEqualTo(1);
    }

    // ── 정상 해제도 acquired_at 을 비운다 ──────────────────────────────────

    @Test
    void releaseStation_alsoClearsAcquiredAt() {
        openSessionRow("s-1", "TTL-CARGO-1");
        sessionMapper.acquireStation("s-1", now, expiredBefore());

        assertThat(sessionMapper.releaseStation("s-1")).isEqualTo(1);

        StationState state = sessionMapper.findState().orElseThrow();
        assertThat(state.getActiveSessionId()).isNull();
        // acquired_at 이 남으면 다음 점유 판정이 낡은 시각을 보게 된다.
        assertThat(state.getAcquiredAt()).isNull();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private void openSessionRow(String sessionId, String cargoId) {
        sessionMapper.insert(new StationSession(sessionId, cargoId));
    }

    private void insertCargo(String cargoId) {
        Cargo cargo = new Cargo();
        cargo.setCargoId(cargoId);
        cargo.setWidth(1.0);
        cargo.setLength(1.0);
        cargo.setHeight(0.8);
        cargo.setVolume(0.8);
        cargo.setCreatedAt(LocalDateTime.now());
        cargo.setUpdatedAt(LocalDateTime.now());
        cargoMapper.insert(cargo);
    }
}
