package com.fast.backend.storage.placement;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.StorageSlotStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PlacementServiceTest {

    private final PlacementService service =
            new PlacementService(new PlacementProperties(0.25, 0.12, 0.05));

    @Test
    void recommend_addsPalletAndClearanceExactlyOnce() {
        PlacementRecommendation result = service.recommend(0.50, List.of(
                candidate("SLOT-A", 0.87, 2.0, StorageSlotStatus.EMPTY)));

        assertThat(result.slotCode()).isEqualTo("SLOT-A");
        assertThat(result.heightRemaining()).isEqualTo(0.25, within(1e-9));
    }

    @Test
    void recommend_usesBestHeightFitBeforeDistance() {
        PlacementRecommendation result = service.recommend(0.50, List.of(
                candidate("NEAR-BUT-TALL", 1.20, 1.0, StorageSlotStatus.EMPTY),
                candidate("FAR-BEST-FIT", 0.90, 10.0, StorageSlotStatus.EMPTY)));

        assertThat(result.slotCode()).isEqualTo("FAR-BEST-FIT");
    }

    @Test
    void recommend_usesTravelDistanceAndSlotCodeAsTieBreakers() {
        PlacementRecommendation result = service.recommend(0.50, List.of(
                candidate("SLOT-C", 0.90, null, StorageSlotStatus.EMPTY),
                candidate("SLOT-B", 0.90, 2.0, StorageSlotStatus.EMPTY),
                candidate("SLOT-A", 0.90, 2.0, StorageSlotStatus.EMPTY)));

        assertThat(result.slotCode()).isEqualTo("SLOT-A");
    }

    @Test
    void recommend_excludesUnavailableAndTooShortSlots() {
        assertThatThrownBy(() -> service.recommend(0.50, List.of(
                candidate("RESERVED", 1.0, 1.0, StorageSlotStatus.RESERVED),
                candidate("TOO-SHORT", 0.86, 1.0, StorageSlotStatus.EMPTY))))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_STORAGE_SLOT);
    }

    @Test
    void recommend_rejectsInvalidCargoHeight() {
        assertThatThrownBy(() -> service.recommend(Double.NaN, List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CARGO_DIMENSION_INVALID);
    }

    private PlacementCandidate candidate(
            String slotCode, double usableHeight, Double travelDistance, StorageSlotStatus status) {
        return new PlacementCandidate(
                slotCode, usableHeight, 0.40, 1.0, 2.0, 180.0, travelDistance, status);
    }
}
