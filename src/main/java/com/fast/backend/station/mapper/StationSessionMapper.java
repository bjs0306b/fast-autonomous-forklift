package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.StationState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** {@code station_session} 접근(FR-202 신규, prompt85). */
@Mapper
public interface StationSessionMapper {

    void insert(StationSession session);

    Optional<StationSession> findBySessionId(String sessionId);

    /**
     * 단일 설비의 현재 점유 세션을 읽는다({@code station_state} 한 행 조인).
     * 점유 중이 아니면 empty.
     */
    Optional<StationSession> findActiveSession();

    /**
     * 설비를 점유한다. 유휴이거나 <b>TTL 이 만료된</b> 점유일 때만 성공한다(0 이면 아직 유효한 세션이
     * 점유 중, prompt106). 만료 판정과 점유를 조건부 UPDATE <b>한 문장</b>으로 처리하므로 두 요청이
     * 동시에 와도 하나만 이긴다 — "만료 확인 후 점유"로 나누면 둘 다 회수에 성공할 수 있다.
     *
     * @param acquiredAt    새로 기록할 점유 시각
     * @param expiredBefore 이 시각 이전에 점유된 세션은 만료로 본다({@code now - ttl})
     */
    int acquireStation(@Param("sessionId") String sessionId,
            @Param("acquiredAt") LocalDateTime acquiredAt,
            @Param("expiredBefore") LocalDateTime expiredBefore);

    /** 점유를 푼다. 소유 세션이 맞을 때만 해제한다(0 이면 이미 다른 세션이 점유 중이거나 비어 있음). */
    int releaseStation(@Param("sessionId") String sessionId);

    /**
     * 측정 결과가 저장된 세션만 종료한다(prompt96 3·20장). 조건부 UPDATE 한 문장이라
     * "확인 후 해제" 사이에 다른 요청이 끼어들 수 없다.
     *
     * @return 1이면 종료 성공, 0이면 활성 세션이 아니거나 측정 결과가 아직 없다
     */
    int releaseStationIfMeasurementExists(@Param("sessionId") String sessionId);

    /** 현재 점유 상태(세션 식별자 + 점유 시각). 유휴여도 행은 존재하며 컬럼이 NULL 이다. */
    Optional<StationState> findState();

    /**
     * 점유 상태를 <b>행 잠금과 함께</b> 읽는다(prompt107). 측정 저장이 "확인 → INSERT" 사이에
     * 세션이 바뀌는 것을 막기 위해 쓴다 — 같은 행을 UPDATE 하는 점유/해제가 이 트랜잭션이
     * 끝날 때까지 대기한다. 반드시 트랜잭션 안에서 호출해야 한다.
     */
    Optional<StationState> findStateForUpdate();

    /**
     * 운영자 강제 해제(prompt106). {@link #releaseStationIfMeasurementExists} 와 달리 <b>측정 행
     * 존재 여부를 보지 않는다</b> — 측정을 저장하지 못한 채 죽은 세션을 푸는 것이 목적이다.
     * 요청 sessionId 가 실제 점유 세션과 같을 때만 해제한다(0 이면 불일치 또는 이미 유휴).
     */
    int forceReleaseStation(@Param("sessionId") String sessionId);
}
