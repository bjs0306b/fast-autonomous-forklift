package com.fast.backend.storage.placement;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.CargoOrientation;
import com.fast.backend.storage.domain.StorageSlotStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Best Fit 적재 위치 추천 순수 로직 테스트(prompt46.md 19장 1~11번). Spring 없이 검증한다.
 */
class PlacementServiceTest {

    // (heightClearance, palletHeightM, maxOverhangRatioExclusive)
    private final PlacementService service =
            new PlacementService(new PlacementProperties(0.0, 0.12, 0.05));
    private final PlacementService serviceWithClearance =
            new PlacementService(new PlacementProperties(0.1, 0.12, 0.05));

    private static Cargo cargo(double w, double l, double h) {
        return Cargo.create("CARGO-001", w, l, h);
    }

    private static PlacementCandidate emptySlot(
            long id, String code, int level, double w, double l, double h, Double dx, Double dy) {
        return new PlacementCandidate(id, code, "RACK-A", level, w, l, h, dx, dy, 180.0, 0.8,
                StorageSlotStatus.EMPTY);
    }

    private static PlacementCandidate slot(
            long id, String code, int level, double w, double l, double h, StorageSlotStatus status) {
        return new PlacementCandidate(id, code, "RACK-A", level, w, l, h, 1.0, 1.0, 180.0, 0.8, status);
    }

    @Test
    void recommend_normalOrientationFits() {
        // cargo 0.8 x 1.1: ROTATED는 1.1 > slotWidth 1.0 이라 불가 → NORMAL만 가능
        PlacementRecommendation r = service.recommend(
                cargo(0.8, 1.1, 0.6), List.of(emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.8, null, null)), null, null);
        assertThat(r.orientation()).isEqualTo(CargoOrientation.NORMAL);
        assertThat(r.slotCode()).isEqualTo("A-01-01");
    }

    @Test
    void recommend_rotatedOrientationOnly() {
        // cargo width 1.1 > slotWidth 1.0 → NORMAL 불가. length 0.8 <= 1.0, width 1.1 <= 1.2 → ROTATED 가능
        PlacementRecommendation r = service.recommend(
                cargo(1.1, 0.8, 0.6), List.of(emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.8, null, null)), null, null);
        assertThat(r.orientation()).isEqualTo(CargoOrientation.ROTATED_90);
    }

    @Test
    void recommend_addsPalletHeightExactlyOnce() {
        // 화물 0.6 + 팔레트 0.12 = 0.72. 슬롯 0.75 면 들어가고, 0.70 이면 안 들어간다.
        // 팔레트를 안 더하면 0.70 슬롯도 통과해 버리고, 두 번 더하면 0.75 슬롯이 떨어진다.
        PlacementRecommendation fits = service.recommend(
                cargo(0.8, 1.0, 0.6), List.of(emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.75, null, null)), null, null);
        assertThat(fits.slotCode()).isEqualTo("A-01-01");
        // 잔여 높이도 팔레트를 포함한 기준이어야 한다: 0.75 - (0.6 + 0.12) = 0.03
        assertThat(fits.heightRemaining()).isEqualTo(0.75 - 0.72, within(1e-9));

        assertThatThrownBy(() -> service.recommend(
                cargo(0.8, 1.0, 0.6), List.of(emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.70, null, null)), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_STORAGE_SLOT);
    }

    @Test
    void recommend_heightExceeded_throws() {
        // cargo height 0.75 + 팔레트 0.12 + clearance 0.1 = 0.97 > slot height 0.8
        assertThatThrownBy(() -> serviceWithClearance.recommend(
                cargo(0.8, 1.0, 0.75), List.of(emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.8, null, null)), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_STORAGE_SLOT);
    }

    @Test
    void recommend_widthAndLengthExceeded_throws() {
        assertThatThrownBy(() -> service.recommend(
                cargo(1.5, 1.5, 0.6), List.of(emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.8, null, null)), null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void recommend_blockedSlotExcluded_throws() {
        assertThatThrownBy(() -> service.recommend(
                cargo(0.8, 1.0, 0.6), List.of(slot(1, "A-01-01", 1, 1.0, 1.2, 0.8, StorageSlotStatus.BLOCKED)), null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void recommend_occupiedSlotExcluded_throws() {
        assertThatThrownBy(() -> service.recommend(
                cargo(0.8, 1.0, 0.6), List.of(slot(1, "A-01-01", 1, 1.0, 1.2, 0.8, StorageSlotStatus.OCCUPIED)), null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void recommend_reservedSlotExcluded_throws() {
        assertThatThrownBy(() -> service.recommend(
                cargo(0.8, 1.0, 0.6), List.of(slot(1, "A-01-01", 1, 1.0, 1.2, 0.8, StorageSlotStatus.RESERVED)), null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void recommend_choosesSmallestWastedVolume() {
        PlacementCandidate small = emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.8, null, null);
        PlacementCandidate big = emptySlot(2, "A-02-01", 1, 2.0, 2.0, 1.5, null, null);
        PlacementRecommendation r = service.recommend(cargo(0.8, 1.0, 0.6), List.of(big, small), null, null);
        assertThat(r.slotId()).isEqualTo(1);
    }

    @Test
    void recommend_sameFit_choosesLowerLevel() {
        PlacementCandidate level2 = emptySlot(1, "A-02-01", 2, 1.0, 1.2, 0.8, null, null);
        PlacementCandidate level1 = emptySlot(2, "A-01-01", 1, 1.0, 1.2, 0.8, null, null);
        PlacementRecommendation r = service.recommend(cargo(0.8, 1.0, 0.6), List.of(level2, level1), null, null);
        assertThat(r.levelNumber()).isEqualTo(1);
    }

    @Test
    void recommend_sameFitAndLevel_choosesNearestToPickup() {
        // 동일 크기·층, pickup(0,0)에서 near dest(1,0) vs far dest(5,0)
        PlacementCandidate near = emptySlot(1, "A-01-01", 1, 1.0, 1.2, 0.8, 1.0, 0.0);
        PlacementCandidate far = emptySlot(2, "A-01-02", 1, 1.0, 1.2, 0.8, 5.0, 0.0);
        PlacementRecommendation r = service.recommend(cargo(0.8, 1.0, 0.6), List.of(far, near), 0.0, 0.0);
        assertThat(r.slotId()).isEqualTo(1);
        assertThat(r.distance()).isEqualTo(1.0);
    }

    @Test
    void recommend_noCandidate_throws() {
        assertThatThrownBy(() -> service.recommend(cargo(0.8, 1.0, 0.6), List.of(), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_STORAGE_SLOT);
    }

    @Test
    void recommend_bothOrientationsFit_choosesSmallerRemaining() {
        // 정사각형에 가까운 cargo가 직사각 슬롯에 들어갈 때, 남는 평면이 더 작은 방향을 고른다.
        // cargo 0.6(w) x 1.0(l): NORMAL 남음=(1.2-0.6)+(1.4-1.0)=1.0, ROTATED 남음=(1.2-1.0)+(1.4-0.6)=1.0 → 동률 시 NORMAL
        PlacementRecommendation r = service.recommend(
                cargo(0.6, 1.0, 0.5), List.of(emptySlot(1, "A-01-01", 1, 1.2, 1.4, 0.8, null, null)), null, null);
        assertThat(r.orientation()).isEqualTo(CargoOrientation.NORMAL);
        assertThat(r.wastedVolume()).isEqualTo(1.2 * 1.4 * 0.8 - 0.6 * 1.0 * 0.5);
    }
}
