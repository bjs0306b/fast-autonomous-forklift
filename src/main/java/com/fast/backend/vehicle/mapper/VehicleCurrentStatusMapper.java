package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface VehicleCurrentStatusMapper {

    Optional<VehicleCurrentStatus> findByVehicleId(String vehicleId);

    List<VehicleCurrentStatus> findAllByVehicleIds(List<String> vehicleIds);

    /**
     * MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE}로 차량당 최신 상태 한 행만 유지한다
     * (vehicle_id가 PK, prompt16.md 7장 조건). "오래된 메시지가 최신 상태를 덮어쓰지 않도록" 하는 비교는
     * SQL이 아니라 {@link com.fast.backend.vehicle.service.VehicleStatusService}에서 upsert 호출 전에
     * 수행한다 — SQL에서 비교하면 실패를 조용히 무시한 것인지 실제로 갱신된 것인지 구분하기 어렵기 때문이다.
     */
    void upsert(VehicleCurrentStatus status);

    /**
     * 활성 차량만 대상으로 상태별 차량 수를 집계한다. 상태 이력이 아예 없는(vehicle_current_status에
     * 행이 없는) 활성 차량도 LEFT JOIN + COALESCE로 UNKNOWN에 포함된다(prompt16.md 14장 분석 결과).
     */
    List<VehicleStatusCountRow> countByStatusForActiveVehicles();
}
