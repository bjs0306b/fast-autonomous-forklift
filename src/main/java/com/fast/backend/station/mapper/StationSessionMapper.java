package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
     * 설비를 점유한다. <b>비어 있을 때만</b> 성공한다(0 이면 이미 다른 세션이 점유 중).
     * 조건부 UPDATE 한 문장이라 두 요청이 동시에 와도 하나만 이긴다.
     */
    int acquireStation(@Param("sessionId") String sessionId);

    /** 점유를 푼다. 소유 세션이 맞을 때만 해제한다(0 이면 이미 다른 세션이 점유 중이거나 비어 있음). */
    int releaseStation(@Param("sessionId") String sessionId);

    /**
     * 측정 결과가 저장된 세션만 종료한다(prompt96 3·20장). 조건부 UPDATE 한 문장이라
     * "확인 후 해제" 사이에 다른 요청이 끼어들 수 없다.
     *
     * @return 1이면 종료 성공, 0이면 활성 세션이 아니거나 측정 결과가 아직 없다
     */
    int releaseStationIfMeasurementExists(@Param("sessionId") String sessionId);
}
