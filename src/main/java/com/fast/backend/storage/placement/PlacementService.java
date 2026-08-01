package com.fast.backend.storage.placement;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.StorageSlotStatus;
import java.util.Comparator;
import java.util.List;

/** 높이 적합성, Nav2 이동 거리, 적재 위치 코드 순으로 적재 위치를 선정한다. */
public class PlacementService {

    private final double heightClearance;
    private final double palletHeight;

    public PlacementService(PlacementProperties properties) {
        this.heightClearance = properties.heightClearance();
        this.palletHeight = properties.palletHeightM();
    }

    public PlacementRecommendation recommend(double cargoHeight, List<PlacementCandidate> candidates) {
        if (!Double.isFinite(cargoHeight) || cargoHeight <= 0) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID,
                    "cargoHeight는 0보다 큰 meter 값이어야 합니다: " + cargoHeight);
        }
        double requiredHeight = cargoHeight + palletHeight + heightClearance;
        return (candidates == null ? List.<PlacementCandidate>of() : candidates).stream()
                .filter(c -> c != null && c.status() == StorageSlotStatus.EMPTY)
                .filter(c -> c.usableHeight() >= requiredHeight)
                .map(c -> new PlacementRecommendation(
                        c.slotCode(), c.destinationX(), c.destinationY(), c.destinationHeading(),
                        c.forkHeight(), c.usableHeight() - (cargoHeight + palletHeight), c.travelDistance()))
                .min(Comparator.comparingDouble(PlacementRecommendation::heightRemaining)
                        .thenComparing(PlacementRecommendation::travelDistance,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlacementRecommendation::slotCode))
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_AVAILABLE_STORAGE_SLOT,
                        "화물 높이에 맞는 빈 적재 위치가 없습니다."));
    }
}
