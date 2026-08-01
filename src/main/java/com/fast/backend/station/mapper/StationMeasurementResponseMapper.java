package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.springframework.stereotype.Component;

@Component
public class StationMeasurementResponseMapper {

    public StationMeasurementResponse toResponse(
            StationMeasurement entity, String cargoId, boolean placementEligible) {
        return new StationMeasurementResponse(
                entity.getMeasurementId(),
                entity.getSessionId(),
                cargoId,
                entity.getStatus(),
                entity.getCargoHeight(),
                entity.getTippingLevel(),
                entity.getOverhangRatio(),
                placementEligible,
                entity.getCreatedAt());
    }
}
