package com.fast.backend.embedded.mapper;

import com.fast.backend.embedded.domain.VehicleForkCurrentStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface VehicleForkCurrentStatusMapper {

    /** vehicle_current_status.upsert()와 동일한 MySQL INSERT ... ON DUPLICATE KEY UPDATE 패턴. */
    void upsert(VehicleForkCurrentStatus status);

    Optional<VehicleForkCurrentStatus> findByForkliftId(String forkliftId);
}
