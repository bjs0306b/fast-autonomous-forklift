package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface StationMeasurementMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 measurement.id가 채워진다. */
    int insert(StationMeasurement measurement);

    boolean existsByMeasurementId(String measurementId);

    Optional<StationMeasurement> findByMeasurementId(String measurementId);

    /** stationId 기준 가장 최근(measured_at_utc DESC) 측정 결과 1건. MQTT 시절 행 조회용 레거시. */
    Optional<StationMeasurement> findLatestByStationId(String stationId);

    /**
     * 세션에 측정 결과가 이미 저장돼 있는지(prompt96).
     *
     * <p>두 곳에서 쓴다 — 세션당 최종 결과 1건 정책(5장)과, 측정 저장 전 세션 종료 차단(3장).
     * 두 번째 용도에서는 status 를 보지 않는다: DIMENSIONS_ONLY/NO_DETECTION/UNRELIABLE 도
     * "측정 결과가 저장됨"에 해당하므로 세션을 종료할 수 있다.
     */
    boolean existsBySessionId(String sessionId);

    /** 세션의 최신 측정 결과 1건. 세션당 1건 정책이라 사실상 그 세션의 유일한 결과다. */
    Optional<StationMeasurement> findLatestBySessionId(String sessionId);
}
