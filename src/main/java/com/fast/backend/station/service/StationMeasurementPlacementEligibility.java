package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.domain.TippingLevel;
import com.fast.backend.storage.placement.PlacementProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 측정 결과가 적재 추천을 실행해도 되는 상태인지 판정한다.
 *
 * <p><b>왜 별도 클래스인가</b>: 이 조건은 두 경로에서 필요하다 — 측정 저장 직후 자동 추천과,
 * 프론트가 따로 호출하는 추천 API. 같은 판정을 두 벌 복사하면 한쪽만 고쳐져 조용히 어긋난다.
 * 판정 로직을 여기 한 곳에 두고 양쪽이 호출한다.
 *
 * <p><b>모두 AND 조건이다.</b> 세 안전값(높이·전복등급·돌출률) 중 하나라도 빠지면 추천하지 않는다 —
 * "높이만 있으면 높이 판단은 가능하지 않나"는 이전 정책이었고, 확정 정책은 셋 다 요구한다.
 * 값이 없을 때 임의로 SAFE 로 보정하거나 기본 슬롯을 돌려주지 않는다.
 *
 * <p><b>실패 원인을 구분해서 던진다.</b> 빈 목록이나 null 로 조용히 반환하면 호출자가
 * "적합한 슬롯이 없음"과 "애초에 추천 대상이 아님"을 구별할 수 없다. 여러 조건이 동시에
 * 실패할 수 있으므로 검증 순서를 고정해(상태 → 높이 → 전복 → 돌출) 같은 입력이 항상 같은
 * 오류를 내게 한다.
 */
@Component
public class StationMeasurementPlacementEligibility {

    private final double maxOverhangRatioExclusive;

    public StationMeasurementPlacementEligibility(PlacementProperties placementProperties) {
        this.maxOverhangRatioExclusive = placementProperties == null
                ? PlacementProperties.DEFAULT_MAX_OVERHANG_RATIO_EXCLUSIVE
                : placementProperties.maxOverhangRatioExclusive();
    }

    /** 추천 가능한지 조용히 묻는다(자동 호출 경로용) — 예외를 던지지 않는다. */
    public boolean isEligible(StationMeasurement measurement) {
        return describeIneligibility(measurement).isEmpty();
    }

    /**
     * 추천 조건을 강제한다(추천 API 진입점용). 조건을 만족하지 않으면 원인별 {@link ErrorCode}로
     * {@link BusinessException}을 던진다.
     */
    public void requireEligible(StationMeasurement measurement) {
        describeIneligibility(measurement).ifPresent(failure -> {
            throw new BusinessException(failure.errorCode(), failure.message());
        });
    }

    /**
     * 검증 순서가 곧 오류 우선순위다: 상태 → 높이 → 전복 등급 → 돌출률.
     *
     * @return 추천 가능하면 empty, 아니면 첫 번째 실패 사유
     */
    private Optional<Failure> describeIneligibility(StationMeasurement measurement) {
        if (measurement == null) {
            return Optional.of(new Failure(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                    "측정 결과가 없어 적재 추천을 할 수 없습니다."));
        }

        // 1. status — OK 가 아니면 나머지 값이 있든 없든 추천 대상이 아니다.
        //    DIMENSIONS_ONLY 는 높이가 있어도 전복·돌출 판정이 없으므로 여기서 걸린다.
        if (measurement.getStatus() != StationMeasurementStatus.OK) {
            return Optional.of(new Failure(ErrorCode.STATION_MEASUREMENT_STATUS_NOT_ELIGIBLE,
                    "status=" + rawStatus(measurement) + " 은 적재 추천 대상이 아닙니다(OK 만 가능)."));
        }

        // 2. 화물 높이 — 없거나 유한하지 않거나 0 이하면 필요 높이를 계산할 수 없다.
        Double cargoHeight = measurement.getCargoHeight();
        if (cargoHeight == null || !Double.isFinite(cargoHeight) || cargoHeight <= 0) {
            return Optional.of(new Failure(ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID,
                    "cargoHeight 가 유효하지 않습니다: " + cargoHeight));
        }

        // 3. 전복 위험 등급 — SAFE 만 통과. 문자열 비교를 흩뿌리지 않고 enum 으로 변환해 본다.
        Optional<TippingLevel> tippingLevel = TippingLevel.fromRaw(measurement.getTippingLevel());
        if (tippingLevel.isEmpty() || tippingLevel.get() != TippingLevel.SAFE) {
            return Optional.of(new Failure(ErrorCode.STATION_TIPPING_LEVEL_NOT_SAFE,
                    "tippingLevel 이 SAFE 가 아닙니다: " + measurement.getTippingLevel()));
        }

        // 4. 돌출률 — 경계 미포함 상한. 0.05 설정이면 0.05 는 차단, 0.049999 는 허용.
        Double overhangRatio = measurement.getOverhangRatio();
        if (overhangRatio == null || !Double.isFinite(overhangRatio) || overhangRatio < 0) {
            return Optional.of(new Failure(ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED,
                    "overhangRatio 가 유효하지 않습니다: " + overhangRatio));
        }
        if (overhangRatio >= maxOverhangRatioExclusive) {
            return Optional.of(new Failure(ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED,
                    "overhangRatio 가 허용 한계 이상입니다: " + overhangRatio
                            + " >= " + maxOverhangRatioExclusive));
        }

        return Optional.empty();
    }

    private String rawStatus(StationMeasurement measurement) {
        return measurement.getStatus() == null ? "null" : measurement.getStatus().rawValue();
    }

    private record Failure(ErrorCode errorCode, String message) {
    }
}
