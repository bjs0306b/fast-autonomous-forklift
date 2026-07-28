package com.fast.backend.command.mapper;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
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

    /**
     * 차량별 최근 명령 목록(issued_at 내림차순).
     *
     * <p>{@code commandCategory}가 null이면 <b>모든 분류</b>를 반환한다(기존 동작 그대로). 값을 주면 해당
     * 분류만 반환한다 — 관제 화면이 "최근 안전 명령"을 물을 때 MOVE/FORK 명령이 섞여 나오지 않게 하기 위한
     * 선택 필터다(prompt56.md 12장 A안).
     */
    List<VehicleCommand> findRecentByVehicleId(
            @Param("vehicleId") String vehicleId,
            @Param("limit") int limit,
            @Param("commandCategory") VehicleCommandCategory commandCategory);
}
