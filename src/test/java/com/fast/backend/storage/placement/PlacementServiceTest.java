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
            new PlacementService(new PlacementProperties(0.25, 0.12, 0.05, 0.10));

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
        return candidate(slotCode, usableHeight, null, travelDistance, status);
    }

    private PlacementCandidate candidate(
            String slotCode, double usableHeight, Double usableWidth,
            Double travelDistance, StorageSlotStatus status) {
        return new PlacementCandidate(
                slotCode, usableHeight, usableWidth, 0.40, 1.0, 2.0, 180.0, travelDistance, status);
    }

    // ── 층 선택 ────────────────────────────────────────────────────────────────
    // 랙에 0층·1층이 생기면서 필요해진 기준이다. 실측(2026-08-10) 기준으로
    // 0층 칸(0.14 m)이 1층 칸(0.0675 m)보다 **높다**. 그래서 "남는 높이 최소" 로만 고르면
    // 작은 화물이 죄다 1층으로 올라간다. 팀 결정은 **낮은 층 먼저**다.

    /** 모형 스케일 설정 — 팔레트 0.012 · 여유 0.025 (application.yml 과 같은 값). */
    private final PlacementService modelScale =
            new PlacementService(new PlacementProperties(0.025, 0.012, 0.05, 0.10));

    /** 0층 = fork 0.0 · usable 0.14 / 1층 = fork 1.325 · usable 0.2 (실측, 2026-08-10) */
    private static PlacementCandidate slot(String code, double usableHeight, double forkHeight) {
        return new PlacementCandidate(
                code, usableHeight, null, forkHeight, 5.0, 9.3, 180.0, 1.0,
                StorageSlotStatus.EMPTY);
    }

    @Test
    void 둘_다_들어가면_낮은_층을_고른다() {
        // 2cm 화물은 0층에도 1층에도 들어간다. 낮은 층 먼저이므로 0층이다.
        PlacementRecommendation result = modelScale.recommend(0.02, List.of(
                slot("A101", 0.2, 1.325),
                slot("AF01", 0.14, 0.0)));

        assertThat(result.slotCode()).isEqualTo("AF01");
        assertThat(result.forkHeight()).isEqualTo(0.0);
    }

    @Test
    void 낮은_층이_차면_위층으로_올라간다() {
        PlacementRecommendation result = modelScale.recommend(0.02, List.of(
                new PlacementCandidate("AF01", 0.14, null, 0.0, 5.0, 9.3, 180.0, 1.0,
                        StorageSlotStatus.OCCUPIED),
                slot("A101", 0.2, 1.325)));

        assertThat(result.slotCode()).isEqualTo("A101");
    }

    @Test
    void 큰_화물은_1층으로_올라간다() {
        // 12cm 화물: required 0.157.  0층(0.14)에는 안 들어가고 1층(0.2)에만 들어간다.
        // 실측상 1층 칸(2.0 시뮬 = 0.2 실물)이 0층 칸(1.325 시뮬 = 0.14 실물)보다 넓다.
        PlacementRecommendation result = modelScale.recommend(0.12, List.of(
                slot("AF01", 0.14, 0.0),
                slot("A101", 0.2, 1.325)));

        assertThat(result.slotCode()).isEqualTo("A101");
        assertThat(result.forkHeight()).isEqualTo(1.325);
    }

    @Test
    void 어느_층에도_안_들어가면_거부한다() {
        // 17cm 화물: required 0.207 > 1층 0.2.
        assertThatThrownBy(() -> modelScale.recommend(0.17, List.of(
                slot("AF01", 0.14, 0.0),
                slot("A101", 0.2, 1.325))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 같은_층이면_예전대로_딱_맞는_칸을_고른다() {
        PlacementRecommendation result = modelScale.recommend(0.02, List.of(
                slot("AF01", 0.14, 0.0),
                slot("AF02", 0.10, 0.0)));

        assertThat(result.slotCode()).isEqualTo("AF02");
    }

    // ── 폭 검사 ────────────────────────────────────────────────────────────────
    // 깊이(depth)는 정면 카메라로 측정할 수 없어(pipeline.py 가 항상 null) 검사 대상이 아니다.
    // 그래서 회전(가로↔세로 교환) 판정도 하지 않는다 — 두 축을 다 알아야 성립한다.

    @Test
    void 폭이_모자라는_슬롯은_제외된다() {
        // 폭 여유 기본값 0.10 → 필요 폭 = 0.80 + 0.10 = 0.90
        List<PlacementCandidate> candidates = List.of(
                candidate("A1", 2.0, 0.85, null, StorageSlotStatus.EMPTY),   // 폭 부족
                candidate("A2", 2.0, 1.20, null, StorageSlotStatus.EMPTY));  // 통과

        PlacementRecommendation result = service.recommend(0.5, 0.80, candidates);

        assertThat(result.slotCode()).isEqualTo("A2");
    }

    @Test
    void 슬롯_폭을_모르면_폭_검사를_건너뛴다() {
        // usableWidth=null 은 "폭 제약을 모른다"는 뜻이다. 컬럼 도입 전 슬롯을 전부
        // 못 쓰게 만들면 안 되므로 통과시킨다.
        List<PlacementCandidate> candidates = List.of(
                candidate("A1", 2.0, null, null, StorageSlotStatus.EMPTY));

        PlacementRecommendation result = service.recommend(0.5, 99.0, candidates);

        assertThat(result.slotCode()).isEqualTo("A1");
    }

    @Test
    void 화물_폭을_모르면_높이로만_고른다() {
        List<PlacementCandidate> candidates = List.of(
                candidate("A1", 2.0, 0.10, null, StorageSlotStatus.EMPTY));

        PlacementRecommendation result = service.recommend(0.5, null, candidates);

        assertThat(result.slotCode()).isEqualTo("A1");
    }

    @Test
    void 폭이_맞는_슬롯이_하나도_없으면_실패한다() {
        List<PlacementCandidate> candidates = List.of(
                candidate("A1", 2.0, 0.50, null, StorageSlotStatus.EMPTY));

        assertThatThrownBy(() -> service.recommend(0.5, 1.00, candidates))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NO_AVAILABLE_STORAGE_SLOT);
    }

    @Test
    void 화물_폭이_0이하면_거부한다() {
        List<PlacementCandidate> candidates = List.of(
                candidate("A1", 2.0, 1.0, null, StorageSlotStatus.EMPTY));

        assertThatThrownBy(() -> service.recommend(0.5, 0.0, candidates))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CARGO_DIMENSION_INVALID);
    }
}
