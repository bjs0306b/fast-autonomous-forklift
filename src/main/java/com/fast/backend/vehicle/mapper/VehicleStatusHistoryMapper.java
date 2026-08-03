package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VehicleStatusHistoryMapper {

    /** 상태 이력 한 건을 append한다. {@code history_id}는 채번 후 인자 객체에 반영된다. */
    void insert(VehicleStatusHistory history);

    /**
     * 차량별 상태 이력을 최신순({@code message_at DESC, history_id DESC})으로 조회한다.
     * {@code from}/{@code to}/{@code status}는 null이면 조건에서 제외한다.
     * 기간은 {@code from <= message_at < to}(시작 포함, 종료 제외)다.
     */
    List<VehicleStatusHistory> findByVehicleId(
            @Param("vehicleId") String vehicleId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") VehicleStatus status,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /** {@link #findByVehicleId}와 같은 조건의 전체 건수. 페이징 메타 계산에 쓴다. */
    long countByVehicleId(
            @Param("vehicleId") String vehicleId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("status") VehicleStatus status);
}
