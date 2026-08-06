package com.fast.backend.traffic.mapper;

import com.fast.backend.traffic.domain.TrafficControlEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrafficControlEventMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 {@code id} 가 채워진다. */
    int insert(TrafficControlEvent event);

    /** 차량별 최근 이벤트. 시연 후 "그때 왜 멈췄나"를 되짚는 용도. */
    List<TrafficControlEvent> findRecentByVehicleId(
            @Param("vehicleId") String vehicleId, @Param("limit") int limit);

    /** 전체 최근 이벤트(차량 무관). */
    List<TrafficControlEvent> findRecent(@Param("limit") int limit);
}
