package com.fast.backend.storage.placement;

/**
 * 적재 위치 추천 결과 — FR-202 최종 스키마(prompt85).
 *
 * <p>{@code orientation}/{@code widthRemaining}/{@code lengthRemaining}/{@code wastedVolume} 이
 * 사라졌다. 평면 치수를 저장하지 않으므로 계산할 근거가 없다.
 * {@code distance} 는 pickup 좌표가 주어졌을 때만 계산되며 없으면 null 이다.
 */
public record PlacementRecommendation(
        String slotCode,
        Double destinationX,
        Double destinationY,
        Double destinationHeading,
        Double forkHeight,
        double heightRemaining,
        Double distance
) {
}
