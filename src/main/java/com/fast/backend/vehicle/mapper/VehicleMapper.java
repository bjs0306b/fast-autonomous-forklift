package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.Vehicle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface VehicleMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 vehicle.id가 채워진다. */
    int insert(Vehicle vehicle);

    Optional<Vehicle> findByVehicleId(String vehicleId);

    boolean existsByVehicleId(String vehicleId);

    int updateActive(
            @Param("vehicleId") String vehicleId,
            @Param("active") boolean active,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** 활성(active=true) 차량만 조회한다. 목록 API 기본 동작(prompt16.md 13장 설계, answer15.md 문서화). */
    List<Vehicle> findAllActive();

    /**
     * 활성 차량 중 {@code vehicle_current_status} 에 좌표를 한 번이라도 남긴 차량만 조회한다.
     *
     * <p>{@code vehicle} 테이블에는 {@code active=true} 로 등록만 돼 있고 MQTT 로 위치·상태를 한 번도
     * 보낸 적 없는 더미/폐기 등록(예: FORKLIFT-01/02, REAL-F01)이 섞여 있을 수 있다. 전체 비상정지처럼
     * "지금 실제로 명령을 받을 수 있는 차량" 수가 화면 표시와 일치해야 하는 곳에 쓴다
     * ({@link com.fast.backend.command.service.SafetyCommandService#emergencyStopAll()}).
     * 단건 명령(vehicleId 지정)에는 이 제약을 걸지 않는다 — 사용자가 이미 특정 차량을 골랐다면
     * 좌표 유무와 무관하게 명령을 시도해야 한다.
     */
    List<Vehicle> findAllActiveWithLocation();

    List<String> findAllActiveVehicleIds();
}
