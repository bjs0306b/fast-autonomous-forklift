package com.fast.backend.storage.placement;

import com.fast.backend.storage.domain.StorageSlotStatus;

/** 높이만으로 판정하는 적재 후보. 사용 가능한 경우 Nav2가 이동 거리를 제공한다. */
public record PlacementCandidate(
        String slotCode,
        double usableHeight,
        double forkHeight,
        double destinationX,
        double destinationY,
        double destinationHeading,
        Double travelDistance,
        StorageSlotStatus status
) {
}
