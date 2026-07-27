package com.fast.backend.storage.placement;

import com.fast.backend.storage.domain.CargoOrientation;

/**
 * 적재 위치 추천 결과(prompt46.md 7장). {@code cargoId}/{@code palletId}는 알고리즘이 아니라 호출 맥락에서
 * 결정되므로 여기에 포함하지 않는다 — API 응답 DTO(추후 구현)가 이 결과에 cargoId/palletId를 덧붙인다.
 *
 * <p>{@code distance}는 pickup 위치가 있을 때만 계산되며, 없으면 {@code null}이다.
 */
public record PlacementRecommendation(
        Long slotId,
        String slotCode,
        String rackCode,
        int levelNumber,
        CargoOrientation orientation,
        Double destinationX,
        Double destinationY,
        Double destinationHeading,
        Double forkHeight,
        double widthRemaining,
        double lengthRemaining,
        double heightRemaining,
        double wastedVolume,
        Double distance
) {
}
