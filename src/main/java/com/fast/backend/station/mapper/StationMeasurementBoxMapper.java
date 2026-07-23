package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurementBox;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface StationMeasurementBoxMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 box.id가 채워진다. */
    int insert(StationMeasurementBox box);

    /** box_order 오름차순으로 부모 측정의 detection box를 조회한다. */
    List<StationMeasurementBox> findByStationMeasurementId(Long stationMeasurementId);
}
