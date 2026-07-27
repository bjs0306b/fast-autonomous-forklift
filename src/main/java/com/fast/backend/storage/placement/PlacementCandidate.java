package com.fast.backend.storage.placement;

import com.fast.backend.storage.domain.StorageSlotStatus;

/**
 * 추천 알고리즘의 입력 읽기 모델(prompt46.md 7장). 영속 계층(추후 구현)이 slot·level·rack 조인 결과를
 * 이 레코드로 만들어 {@link PlacementService}에 넘긴다 — 알고리즘을 특정 Mapper/조인 구조에 묶지 않고
 * 순수하게(단위 테스트 가능하게) 유지하기 위해서다.
 *
 * <p>단위: {@code slotWidth}/{@code slotLength}/{@code slotHeight}, {@code destinationX/Y}, {@code forkHeight}는 m,
 * {@code destinationHeading}은 degree.
 */
public record PlacementCandidate(
        Long slotId,
        String slotCode,
        String rackCode,
        int levelNumber,
        double slotWidth,
        double slotLength,
        double slotHeight,
        Double destinationX,
        Double destinationY,
        Double destinationHeading,
        Double forkHeight,
        StorageSlotStatus status
) {
}
