package com.fast.backend.loadsafety.mapper;

import com.fast.backend.loadsafety.domain.VehicleLoadSafety;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

/** 차량별 최신 적재 안전 상태 저장소. {@code vehicle_fork_current_status}와 동일한 차량당 1행 upsert 구조다. */
@Mapper
public interface VehicleLoadSafetyMapper {

    void upsert(VehicleLoadSafety loadSafety);

    Optional<VehicleLoadSafety> findByVehicleId(String vehicleId);

    /** 활성 차량 전체의 최신 적재 안전 상태(vehicleId 오름차순). 미수신 차량은 행 자체가 없다. */
    List<VehicleLoadSafety> findAllActive();
}
