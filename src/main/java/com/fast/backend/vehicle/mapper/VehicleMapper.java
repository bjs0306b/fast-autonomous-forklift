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

    List<String> findAllActiveVehicleIds();
}
