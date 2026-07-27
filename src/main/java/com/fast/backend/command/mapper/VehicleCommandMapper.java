package com.fast.backend.command.mapper;

import com.fast.backend.command.domain.VehicleCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * 통합 차량 명령 Mapper(구 {@code EmbeddedVehicleCommandMapper} 이관).
 * 도메인 필드 {@code vehicleId}는 XML에서 {@code forklift_id} 컬럼과 매핑된다
 * ({@link VehicleCommand} Javadoc의 컬럼 이름 유지 근거 참고).
 */
@Mapper
public interface VehicleCommandMapper {

    void insert(VehicleCommand command);

    void update(VehicleCommand command);

    boolean existsByCommandId(String commandId);

    Optional<VehicleCommand> findByCommandId(String commandId);

    List<VehicleCommand> findRecentByVehicleId(
            @Param("vehicleId") String vehicleId,
            @Param("limit") int limit);
}
