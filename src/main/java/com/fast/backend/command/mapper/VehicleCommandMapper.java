package com.fast.backend.command.mapper;

import com.fast.backend.command.domain.VehicleCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/** 통합 {@code vehicle_command} Mapper. */
@Mapper
public interface VehicleCommandMapper {

    void insert(VehicleCommand command);

    void update(VehicleCommand command);

    boolean existsByCommandId(String commandId);

    Optional<VehicleCommand> findByCommandId(String commandId);

    /** 차량별 최근 명령 목록(created_at 내림차순). */
    List<VehicleCommand> findRecentByVehicleId(
            @Param("vehicleId") String vehicleId,
            @Param("limit") int limit);
}
