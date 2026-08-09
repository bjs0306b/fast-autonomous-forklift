package com.fast.backend.storage.placement;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.StorageSlotStatus;
import java.util.Comparator;
import java.util.List;

/**
 * 높이·폭 적합성, Nav2 이동 거리, 적재 위치 코드 순으로 적재 위치를 선정한다.
 *
 * <p><b>깊이(depth)는 검사하지 않는다.</b> 검사를 빠뜨린 것이 아니라 <b>측정할 수 없기 때문</b>이다 —
 * 스테이션 카메라가 정면 하나뿐이라 {@code ai/src/station/pipeline.py} 가 {@code depth_cm} 을
 * 항상 {@code null} 로 낸다(규격 v1.0에 그렇게 명시돼 있다). 깊이가 필요하면 측면/상단 카메라나
 * 별도 센서가 먼저 있어야 하고, 그 전에는 어떤 값을 넣어도 추정치일 뿐이다.
 *
 * <p>같은 이유로 <b>화물 회전(가로↔세로 교환) 판정도 하지 않는다.</b> 회전 검사는 두 축을 모두
 * 알아야 성립한다.
 */
public class PlacementService {

    private final double heightClearance;
    private final double palletHeight;
    private final double widthClearance;

    public PlacementService(PlacementProperties properties) {
        this.heightClearance = properties.heightClearance();
        this.palletHeight = properties.palletHeightM();
        this.widthClearance = properties.widthClearance();
    }

    /** 폭을 모르는 화물(구 측정 결과 등). 높이만으로 판정한다. */
    public PlacementRecommendation recommend(double cargoHeight, List<PlacementCandidate> candidates) {
        return recommend(cargoHeight, null, candidates);
    }

    /**
     * 팔레트를 <b>포함한</b> 전체 높이로 추천한다 — 차량이 보고한 값을 쓸 때.
     *
     * <p>{@link #recommend} 는 AI 가 잰 <b>화물만의</b> 높이를 받아 팔레트를 스스로 더한다.
     * 반면 시뮬 telemetry 의 {@code cargo.h} 는 "팔레트가 포함된 전체 크기" 다
     * ({@code IsaacVehicleTelemetryMessage.Cargo} 주석). 그대로 {@link #recommend} 에 넣으면
     * 팔레트가 두 번 더해져 실제보다 높은 화물로 판정된다.
     *
     * <p>그래서 여기서는 팔레트를 더하지 않고 여유만 얹는다. 두 진입점을 나눠 두는 이유는
     * <b>호출부가 어느 쪽 값을 들고 있는지 이름으로 드러나게</b> 하기 위해서다 — 숫자만 넘기면
     * 어느 쪽인지 알 수 없고, 그 혼동이 정확히 이 버그를 만든다.
     */
    public PlacementRecommendation recommendByTotalHeight(
            double totalHeight, List<PlacementCandidate> candidates) {
        if (!Double.isFinite(totalHeight) || totalHeight <= 0) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID,
                    "totalHeight 는 0 보다 큰 meter 값이어야 합니다: " + totalHeight);
        }
        // 팔레트 높이를 빼서 recommend 에 넘긴다 — 거기서 다시 더하므로 결과는 같고,
        // 필터·정렬 규칙이 한 곳에만 남는다.
        return recommend(totalHeight - palletHeight, null, candidates);
    }

    /**
     * 적재 위치를 고른다.
     *
     * @param cargoHeight 화물만의 높이(m). 팔레트 높이는 이 안에 포함하지 않는다
     * @param cargoWidth  화물 폭(m). {@code null} 이면 폭 검사를 건너뛴다 —
     *                    <b>0 이나 임의값으로 채우지 말 것.</b> 모르는 값을 채우면 맞지도 않는
     *                    슬롯을 고르거나 멀쩡한 슬롯을 전부 탈락시킨다
     */
    public PlacementRecommendation recommend(
            double cargoHeight, Double cargoWidth, List<PlacementCandidate> candidates) {

        if (!Double.isFinite(cargoHeight) || cargoHeight <= 0) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID,
                    "cargoHeight는 0보다 큰 meter 값이어야 합니다: " + cargoHeight);
        }
        if (cargoWidth != null && (!Double.isFinite(cargoWidth) || cargoWidth <= 0)) {
            throw new BusinessException(ErrorCode.CARGO_DIMENSION_INVALID,
                    "cargoWidth는 0보다 큰 meter 값이거나 null이어야 합니다: " + cargoWidth);
        }

        double requiredHeight = cargoHeight + palletHeight + heightClearance;
        // 팔레트를 더하지 않는다 — 팔레트는 화물 아래에 깔리므로 높이만 늘리고 폭은 늘리지 않는다.
        Double requiredWidth = cargoWidth == null ? null : cargoWidth + widthClearance;

        return (candidates == null ? List.<PlacementCandidate>of() : candidates).stream()
                .filter(c -> c != null && c.status() == StorageSlotStatus.EMPTY)
                .filter(c -> c.usableHeight() >= requiredHeight)
                .filter(c -> fitsWidth(c, requiredWidth))
                .map(c -> new PlacementRecommendation(
                        c.slotCode(), c.destinationX(), c.destinationY(), c.destinationHeading(),
                        c.forkHeight(), c.usableHeight() - (cargoHeight + palletHeight), c.travelDistance()))
                // 낮은 층 먼저(2026-08-10 팀 결정). 포크를 덜 올리는 쪽이 빠르고, 흔들림도
                // 적재 실패도 적다. 랙에 층이 생기면서 필요해진 기준이다 — 0층 칸 0.14 m,
                // 1층 칸 0.2 m 라 1층이 더 넓고, "남는 높이 최소" 로만 고르면 큰 화물과 섞였을 때
                // 순서가 뒤집힌다. 위험을 위로 쌓지 않는 쪽을 기본으로 둔다.
                //
                // 층이 같을 때에야 예전 기준(딱 맞는 칸 → 가까운 칸)이 순서를 정한다.
                .min(Comparator.comparingDouble(PlacementRecommendation::forkHeight)
                        .thenComparingDouble(PlacementRecommendation::heightRemaining)
                        .thenComparing(PlacementRecommendation::travelDistance,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(PlacementRecommendation::slotCode))
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_AVAILABLE_STORAGE_SLOT,
                        "화물 크기에 맞는 빈 적재 위치가 없습니다."));
    }

    /**
     * 폭이 들어가는가.
     *
     * <p>둘 중 하나라도 모르면 <b>통과시킨다</b>(제약을 걸 근거가 없다). 특히 슬롯 폭이 없는 것은
     * 컬럼 도입 전에 등록된 슬롯이라는 뜻이라, 여기서 탈락시키면 기존 슬롯이 전부 못 쓰게 된다.
     */
    private boolean fitsWidth(PlacementCandidate candidate, Double requiredWidth) {
        if (requiredWidth == null || candidate.usableWidth() == null) {
            return true;
        }
        return candidate.usableWidth() >= requiredWidth;
    }
}
