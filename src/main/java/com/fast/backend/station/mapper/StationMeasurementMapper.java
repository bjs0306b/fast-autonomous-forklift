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

    /** stationId 기준 가장 최근(measured_at_utc DESC) 측정 결과 1건. */
    Optional<StationMeasurement> findLatestByStationId(String stationId);
}
