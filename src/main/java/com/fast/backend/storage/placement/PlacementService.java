package com.fast.backend.storage.placement;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.StorageSlotStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 적재 위치 추천 — FR-202 최종 스키마(prompt85)에 맞춰 <b>높이 적합성 + 거리</b>로만 판정한다.
 *
 * <p><b>⚠ 알고리즘이 축소됐다. 반드시 읽을 것.</b>
 * 옛 구현은 화물 폭·길이와 슬롯 폭·길이를 비교해 정방향/90도 회전 중 들어가는 방향을 고르고
 * (Best Fit) 남는 부피가 가장 적은 슬롯을 선택했다. 최종 스키마에는 <b>화물 치수도 슬롯 평면 치수도
 * 없다</b>({@code cargo} 는 식별자만, {@code storage_slot} 은 {@code usable_height} 만).
 * 그래서 이 구현은 다음을 <b>보장하지 못한다</b>.
 * <ul>
 *   <li>화물이 슬롯 평면에 실제로 들어가는지 — <b>폭·길이가 슬롯보다 커도 추천된다</b></li>
 *   <li>적재 방향(정방향/회전) 판정</li>
 *   <li>공간 낭비 최소화(Best Fit)</li>
 * </ul>
 * 즉 <b>물리적으로 들어가지 않는 슬롯을 추천할 수 있다.</b> 평면 적합성이 필요하면 화물 폭·길이와
 * 슬롯 폭·길이를 다시 저장해야 한다(스키마 축소의 대가이며, 코드로 메울 수 있는 문제가 아니다).
 *
 * <p>현재 판정 규칙
 * <ol>
 *   <li>{@code status == EMPTY} 인 후보만 본다</li>
 *   <li>{@code cargoHeight + heightClearance <= usableHeight} 를 만족해야 한다</li>
 *   <li>남는 후보 중 pickup 지점에서 가까운 순 → 여유 높이가 작은 순(딱 맞는 칸 우선) → slotCode 순</li>
 * </ol>
 */
public class PlacementService {

    private final double heightClearance;

    public PlacementService(PlacementProperties placementProperties) {
        this.heightClearance = placementProperties == null ? 0.0 : placementProperties.heightClearance();
    }

    /**
     * @param cargoHeight 측정된 화물 높이(m). {@code station_measurement.cargo_height} 에서 온다.
     *                    값이 없으면 적합성을 판단할 수 없어 예외를 던진다 — 추측하지 않는다.
     * @param candidates  후보 슬롯
     * @param pickupX     픽업 X(m). null 이면 거리 우선순위를 생략한다.
     * @param pickupY     픽업 Y(m)
     */
    public PlacementRecommendation recommend(
            Double cargoHeight, List<PlacementCandidate> candidates, Double pickupX, Double pickupY) {
        if (cargoHeight == null || cargoHeight <= 0 || !Double.isFinite(cargoHeight)) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID,
                    "측정된 화물 높이가 없어 적재 위치를 추천할 수 없습니다.");
        }

        List<PlacementRecommendation> feasible = new ArrayList<>();
        if (candidates != null) {
            for (PlacementCandidate candidate : candidates) {
                if (candidate == null || candidate.status() != StorageSlotStatus.EMPTY) {
                    continue; // EMPTY 만 추천 대상(BLOCKED/OCCUPIED/RESERVED 제외)
                }
                if (cargoHeight + heightClearance > candidate.usableHeight()) {
                    continue;
                }
                feasible.add(new PlacementRecommendation(
                        candidate.slotCode(),
                        candidate.destinationX(),
                        candidate.destinationY(),
                        candidate.destinationHeading(),
                        candidate.forkHeight(),
                        candidate.usableHeight() - cargoHeight,
                        distance(pickupX, pickupY, candidate.destinationX(), candidate.destinationY())));
            }
        }

        return feasible.stream()
                .min(recommendationComparator())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_AVAILABLE_STORAGE_SLOT,
                        "화물 높이가 들어갈 수 있는 빈 슬롯이 없습니다: cargoHeight=" + cargoHeight));
    }

    /**
     * 거리 → 여유 높이 → slotCode 순.
     *
     * <p>거리를 못 구한 후보(좌표 없음)는 뒤로 보낸다 — 거리 정보가 있는 후보를 우선한다.
     * 마지막에 slotCode 를 넣는 이유는 결과가 실행마다 달라지지 않게 하려는 것이다(동점 처리).
     */
    private Comparator<PlacementRecommendation> recommendationComparator() {
        return Comparator
                .comparing(PlacementRecommendation::distance,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingDouble(PlacementRecommendation::heightRemaining)
                .thenComparing(PlacementRecommendation::slotCode);
    }

    private Double distance(Double fromX, Double fromY, Double toX, Double toY) {
        if (fromX == null || fromY == null || toX == null || toY == null) {
            return null;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
