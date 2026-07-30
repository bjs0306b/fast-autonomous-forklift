package com.fast.backend.storage.placement;

import com.fast.backend.storage.domain.StorageSlotStatus;

/**
 * 추천 알고리즘의 입력 읽기 모델 — FR-202 최종 스키마(prompt85).
 *
 * <p><b>평면 치수가 없다.</b> 최종 스키마의 {@code storage_slot} 은 {@code usable_height} 와
 * {@code fork_height} 만 갖는다(랙 계층·슬롯 폭/길이 제거). 그래서 후보 판정은 높이 적합성과
 * 거리로만 이뤄진다 — 자세한 한계는 {@link PlacementService} Javadoc 참고.
 *
 * <p>단위: {@code usableHeight}/{@code forkHeight}/{@code destinationX,Y} 는 m,
 * {@code destinationHeading} 은 degree.
 */
public record PlacementCandidate(
        String slotCode,
        double usableHeight,
        Double forkHeight,
        Double destinationX,
        Double destinationY,
        Double destinationHeading,
        StorageSlotStatus status
) {
}
