package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/** {@code station_measurement}(FR-202 세션 기반 8컬럼 구조) 접근. */
@Mapper
public interface StationMeasurementMapper {

    /** insert 후 useGeneratedKeys 설정(XML)에 의해 sequenceNo가 채워진다. */
    int insert(StationMeasurement measurement);

    boolean existsByMeasurementId(String measurementId);

    Optional<StationMeasurement> findByMeasurementId(String measurementId);

    /**
     * 세션의 최신 측정 결과 1건.
     *
     * <p>정렬 기준은 {@code sequence_no DESC} — <b>수신 순서</b>다. 센서 측정 시각으로 정렬하지
     * 않는다(그 값은 이 테이블에 없다).
     */
    Optional<StationMeasurement> findLatestBySessionId(String sessionId);
}
