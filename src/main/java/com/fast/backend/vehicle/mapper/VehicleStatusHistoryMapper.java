package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface VehicleStatusHistoryMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 history.id가 채워진다. */
    int insert(VehicleStatusHistory history);

    List<VehicleStatusHistory> findRecentByVehicleId(
            @Param("vehicleId") String vehicleId,
            @Param("limit") int limit);
}
