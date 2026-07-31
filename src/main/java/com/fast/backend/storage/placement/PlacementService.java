package com.fast.backend.storage.placement;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.domain.CargoOrientation;
import com.fast.backend.storage.domain.StorageSlotStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 화물 크기 기반 Best Fit 적재 위치 추천(prompt46.md 6·7장). Spring에 의존하지 않는 순수 로직 클래스라
 * 단위 테스트에서 {@code new PlacementService(new PlacementProperties(0.05))}로 바로 검증할 수 있고,
 * 실제 실행 시에는 {@link PlacementConfig}가 빈으로 등록한다.
 *
 * <p><b>적재 가능 판정은 volume이 아니라 가로·세로·높이를 각각 비교</b>한다(4장). 화물은 두 방향
 * ({@link CargoOrientation#NORMAL}, {@link CargoOrientation#ROTATED_90})으로 놓을 수 있고, 높이 조건은
 * 두 방향 공통으로 {@code cargo.height + palletHeightM + heightClearance <= slot.height}다.
 *
 * <p><b>한 슬롯에서 두 방향이 모두 가능하면 남는 평면 공간(widthRemaining+lengthRemaining)이 더 작은
 * 방향</b>을 고른다(6장). 슬롯 간 우선순위는 7장 순서를 그대로 따른다:
 * <ol>
 *   <li>실제로 들어가는 슬롯만(필터)</li>
 *   <li>wastedVolume이 가장 작은 슬롯</li>
 *   <li>widthRemaining+lengthRemaining+heightRemaining이 가장 작은 슬롯</li>
 *   <li>levelNumber가 낮은 슬롯</li>
 *   <li>pickup이 있으면 pickup↔destination 거리가 가까운 슬롯</li>
 *   <li>그래도 같으면 slotCode 오름차순</li>
 * </ol>
 *
 * <p><b>heightRemaining 정의</b>: {@code slot.height - (cargo.height + palletHeightM)}(적재물 위 물리적 잔여 높이). heightClearance는
 * "적재 가능 여부"를 판단하는 여유 마진으로만 쓰고 잔여 높이 계산에서 빼지 않는다. wastedVolume은 7장 공식대로
 * {@code slot부피 - cargo부피}이며 clearance를 포함하지 않는다.
 *
 * <p><b>팔레트 높이(prompt95 3장, prompt96 9장)</b>: 화물은 항상 팔레트에 실려 운반되므로 랙 간섭
 * 판정에는 팔레트 높이를 더해야 한다. 그 값은 설정 {@code storage.placement.pallet-height-m}에서
 * 오며(코드에 0.12를 박지 않는다), <b>이 클래스가 더하는 유일한 지점</b>이다 — 측정 저장 시점이나
 * DTO 변환에서 미리 더하면 이중 가산이 된다.
 *
 * <p>추천 가능한 슬롯이 없으면 {@link ErrorCode#NO_AVAILABLE_STORAGE_SLOT}을 던진다. 이는 "안전
 * 조건은 통과했지만 맞는 칸이 없다"는 뜻이며, 애초에 추천 대상이 아닌 경우
 * ({@code StationMeasurementPlacementEligibility})와 구분된다.
 */
public class PlacementService {

    private final double heightClearance;
    private final double palletHeightM;

    public PlacementService(PlacementProperties placementProperties) {
        this.heightClearance = placementProperties.heightClearance();
        this.palletHeightM = placementProperties.palletHeightM();
    }

    /**
     * @param cargo     적재할 화물(크기)
     * @param candidates 후보 슬롯(영속 계층이 조인해 만든 읽기 모델). EMPTY가 아닌 슬롯은 내부에서 제외한다.
     * @param pickupX   팔레트 pickup X(m). null이면 거리 우선순위는 생략된다.
     * @param pickupY   팔레트 pickup Y(m)
     * @return Best Fit 추천 슬롯
     * @throws BusinessException 적재 가능한 슬롯이 없으면 NO_AVAILABLE_STORAGE_SLOT
     */
    public PlacementRecommendation recommend(
            Cargo cargo, List<PlacementCandidate> candidates, Double pickupX, Double pickupY) {
        if (cargo == null) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID, "cargo는 필수입니다.");
        }
        List<PlacementRecommendation> feasible = new ArrayList<>();
        if (candidates != null) {
            for (PlacementCandidate candidate : candidates) {
                if (candidate == null || candidate.status() != StorageSlotStatus.EMPTY) {
                    continue; // EMPTY만 추천 대상(BLOCKED/OCCUPIED/RESERVED 제외)
                }
                PlacementRecommendation evaluated = evaluate(cargo, candidate, pickupX, pickupY);
                if (evaluated != null) {
                    feasible.add(evaluated);
                }
            }
        }

        return feasible.stream()
                .min(recommendationComparator())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_AVAILABLE_STORAGE_SLOT,
                        "화물이 들어갈 수 있는 빈 슬롯이 없습니다: cargoId=" + cargo.getCargoId()));
    }

    /** 한 후보 슬롯에 대해 방향을 판정·선택하고 잔여 공간을 계산한다. 들어가지 않으면 null. */
    private PlacementRecommendation evaluate(
            Cargo cargo, PlacementCandidate slot, Double pickupX, Double pickupY) {
        // 팔레트 높이를 여기서 정확히 한 번 더한다 — 화물 높이에는 팔레트가 포함돼 있지 않다.
        double totalLoadHeight = cargo.getHeight() + palletHeightM;
        boolean heightFits = totalLoadHeight + heightClearance <= slot.slotHeight();
        if (!heightFits) {
            return null;
        }
        boolean normalFits = cargo.getWidth() <= slot.slotWidth() && cargo.getLength() <= slot.slotLength();
        boolean rotatedFits = cargo.getLength() <= slot.slotWidth() && cargo.getWidth() <= slot.slotLength();
        if (!normalFits && !rotatedFits) {
            return null;
        }

        CargoOrientation orientation;
        double widthRemaining;
        double lengthRemaining;
        if (normalFits && rotatedFits) {
            double normalRemaining = (slot.slotWidth() - cargo.getWidth()) + (slot.slotLength() - cargo.getLength());
            double rotatedRemaining = (slot.slotWidth() - cargo.getLength()) + (slot.slotLength() - cargo.getWidth());
            if (rotatedRemaining < normalRemaining) {
                orientation = CargoOrientation.ROTATED_90;
                widthRemaining = slot.slotWidth() - cargo.getLength();
                lengthRemaining = slot.slotLength() - cargo.getWidth();
            } else {
                orientation = CargoOrientation.NORMAL;
                widthRemaining = slot.slotWidth() - cargo.getWidth();
                lengthRemaining = slot.slotLength() - cargo.getLength();
            }
        } else if (normalFits) {
            orientation = CargoOrientation.NORMAL;
            widthRemaining = slot.slotWidth() - cargo.getWidth();
            lengthRemaining = slot.slotLength() - cargo.getLength();
        } else {
            orientation = CargoOrientation.ROTATED_90;
            widthRemaining = slot.slotWidth() - cargo.getLength();
            lengthRemaining = slot.slotLength() - cargo.getWidth();
        }

        // 잔여 높이도 팔레트를 포함한 실제 적재 높이 기준이어야 한다 — 판정과 기준이 어긋나면
        // "들어간다고 했는데 여유가 음수"가 나온다.
        double heightRemaining = slot.slotHeight() - totalLoadHeight;
        double wastedVolume = slot.slotWidth() * slot.slotLength() * slot.slotHeight()
                - cargo.getWidth() * cargo.getLength() * cargo.getHeight();
        Double distance = distance(pickupX, pickupY, slot.destinationX(), slot.destinationY());

        return new PlacementRecommendation(
                slot.slotId(), slot.slotCode(), slot.rackCode(), slot.levelNumber(), orientation,
                slot.destinationX(), slot.destinationY(), slot.destinationHeading(), slot.forkHeight(),
                widthRemaining, lengthRemaining, heightRemaining, wastedVolume, distance);
    }

    private Comparator<PlacementRecommendation> recommendationComparator() {
        return Comparator
                .comparingDouble(PlacementRecommendation::wastedVolume)
                .thenComparingDouble(r -> r.widthRemaining() + r.lengthRemaining() + r.heightRemaining())
                .thenComparingInt(PlacementRecommendation::levelNumber)
                .thenComparing(PlacementRecommendation::distance,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PlacementRecommendation::slotCode,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /** pickup·destination 좌표가 모두 있으면 유클리드 거리, 하나라도 없으면 null(거리 조건 생략). */
    private Double distance(Double pickupX, Double pickupY, Double destinationX, Double destinationY) {
        if (pickupX == null || pickupY == null || destinationX == null || destinationY == null) {
            return null;
        }
        double dx = pickupX - destinationX;
        double dy = pickupY - destinationY;
        return Math.sqrt(dx * dx + dy * dy);
    }
}
