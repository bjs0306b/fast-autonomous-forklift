package com.fast.backend.storage.placement;

import com.fast.backend.storage.domain.StorageSlotStatus;

/** 높이만으로 판정하는 적재 후보. 사용 가능한 경우 Nav2가 이동 거리를 제공한다. */
public record PlacementCandidate(
        String slotCode,
        double usableHeight,
        /**
         * 수평 가용 폭(m). {@code null} 이면 <b>폭 제약을 모른다</b>는 뜻이며 폭 검사를 건너뛴다 —
         * 컬럼이 없던 시절에 등록된 슬롯이 그렇다. 0 으로 두면 모든 화물이 탈락하므로 쓰면 안 된다.
         */
        Double usableWidth,
        double forkHeight,
        double destinationX,
        double destinationY,
        double destinationHeading,
        Double travelDistance,
        StorageSlotStatus status
) {
}
