package com.fast.backend.storage.placement;

public record PlacementRecommendation(
        String slotCode,
        double destinationX,
        double destinationY,
        double destinationHeading,
        double forkHeight,
        double heightRemaining,
        Double travelDistance
) {
}
