package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.storage.placement.PlacementProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 적재 추천 안전 게이트(prompt96.md 7·8·11·12·13장).
 *
 * <p>세 안전값(높이·전복등급·돌출률)이 <b>모두</b> 정상일 때만 추천한다. 하나만 정상인 경우들을
 * 각각 고정해, 나중에 "높이는 있으니까 추천해도 되지 않나" 같은 완화가 조용히 들어오지 않게 한다.
 *
 * <p>경계값(0.05)은 <b>미포함</b>이다 — 0.049999 는 허용, 0.05 는 차단. 부동소수 비교라
 * 경계 양쪽을 모두 테스트한다.
 */
class StationMeasurementPlacementEligibilityTest {

    private final StationMeasurementPlacementEligibility eligibility =
            new StationMeasurementPlacementEligibility(new PlacementProperties(0.05, 0.12, 0.05));

    // ── 통과 ────────────────────────────────────────────────────────────────

    @Test
    void eligible_whenOkAndSafeAndUnderOverhangLimit() {
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.0))).isTrue();
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.02))).isTrue();
        // 경계 바로 아래는 허용된다.
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.049999))).isTrue();
    }

    // ── status 게이트 ───────────────────────────────────────────────────────

    @Test
    void ineligible_whenStatusIsNotOk_evenIfHeightPresent() {
        // DIMENSIONS_ONLY 는 높이가 있어도 전복·돌출 판정이 없으므로 추천 대상이 아니다.
        assertRejected(measurement(StationMeasurementStatus.DIMENSIONS_ONLY, 0.723, null, null),
                ErrorCode.STATION_MEASUREMENT_STATUS_NOT_ELIGIBLE);
        assertRejected(measurement(StationMeasurementStatus.NO_DETECTION, null, null, null),
                ErrorCode.STATION_MEASUREMENT_STATUS_NOT_ELIGIBLE);
        assertRejected(measurement(StationMeasurementStatus.UNRELIABLE, null, null, null),
                ErrorCode.STATION_MEASUREMENT_STATUS_NOT_ELIGIBLE);
    }

    // ── 높이 게이트 ─────────────────────────────────────────────────────────

    @Test
    void ineligible_whenCargoHeightMissingOrNotPositiveOrNotFinite() {
        assertRejected(measurement(StationMeasurementStatus.OK, null, "SAFE", 0.0),
                ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.0, "SAFE", 0.0),
                ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID);
        assertRejected(measurement(StationMeasurementStatus.OK, -0.5, "SAFE", 0.0),
                ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID);
        assertRejected(measurement(StationMeasurementStatus.OK, Double.NaN, "SAFE", 0.0),
                ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID);
        assertRejected(measurement(StationMeasurementStatus.OK, Double.POSITIVE_INFINITY, "SAFE", 0.0),
                ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID);
    }

    // ── 전복 등급 게이트 ────────────────────────────────────────────────────

    @Test
    void ineligible_whenTippingLevelIsNotSafe() {
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "WARNING", 0.02),
                ErrorCode.STATION_TIPPING_LEVEL_NOT_SAFE);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "DANGER", 0.0),
                ErrorCode.STATION_TIPPING_LEVEL_NOT_SAFE);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, null, 0.0),
                ErrorCode.STATION_TIPPING_LEVEL_NOT_SAFE);
        // 알 수 없는 문자열을 SAFE 로 봐주지 않는다.
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "critical", 0.0),
                ErrorCode.STATION_TIPPING_LEVEL_NOT_SAFE);
    }

    @Test
    void tippingLevel_isComparedCaseInsensitively() {
        // DB 에는 대문자로 저장되지만, 소문자가 섞여 들어와도 등급 자체는 같은 뜻이다.
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, 0.723, "safe", 0.0))).isTrue();
    }

    // ── 돌출률 게이트 ───────────────────────────────────────────────────────

    @Test
    void ineligible_whenOverhangRatioAtOrAboveLimit() {
        // 경계 미포함: 0.05 는 차단이다.
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.05),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.050001),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 1.4),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
    }

    @Test
    void ineligible_whenOverhangRatioMissingNegativeOrNotFinite() {
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", null),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", -0.01),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", Double.NaN),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", Double.POSITIVE_INFINITY),
                ErrorCode.STATION_OVERHANG_LIMIT_EXCEEDED);
    }

    // ── 조합·순서 ───────────────────────────────────────────────────────────

    @Test
    void onlyOneValueBeingValid_isNotEnough() {
        // 높이만 정상
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, 0.723, null, null))).isFalse();
        // 등급만 정상
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, null, "SAFE", null))).isFalse();
        // 돌출률만 정상
        assertThat(eligibility.isEligible(measurement(StationMeasurementStatus.OK, null, null, 0.01))).isFalse();
    }

    @Test
    void failureOrderIsFixed_statusThenHeightThenTippingThenOverhang() {
        // 네 조건이 동시에 실패해도 항상 status 오류가 먼저 나온다 — 같은 입력이 늘 같은 오류를 내야
        // 호출자가 분기를 신뢰할 수 있다.
        assertRejected(measurement(StationMeasurementStatus.NO_DETECTION, null, "DANGER", 9.9),
                ErrorCode.STATION_MEASUREMENT_STATUS_NOT_ELIGIBLE);
        // status 통과 후에는 높이가 먼저.
        assertRejected(measurement(StationMeasurementStatus.OK, null, "DANGER", 9.9),
                ErrorCode.STATION_MEASUREMENT_HEIGHT_INVALID);
        // 높이 통과 후에는 등급이 먼저.
        assertRejected(measurement(StationMeasurementStatus.OK, 0.723, "DANGER", 9.9),
                ErrorCode.STATION_TIPPING_LEVEL_NOT_SAFE);
    }

    @Test
    void limitComesFromConfiguration_notHardcoded() {
        // 상한을 0.10 으로 올리면 0.05 가 허용된다 — 0.05 가 코드에 박혀 있지 않다는 증거다.
        StationMeasurementPlacementEligibility relaxed =
                new StationMeasurementPlacementEligibility(new PlacementProperties(0.05, 0.12, 0.10));
        assertThat(relaxed.isEligible(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.05))).isTrue();
        assertThat(relaxed.isEligible(measurement(StationMeasurementStatus.OK, 0.723, "SAFE", 0.10))).isFalse();
    }

    @Test
    void nullMeasurement_isRejectedRatherThanCrashing() {
        assertRejected(null, ErrorCode.STATION_MEASUREMENT_NOT_FOUND);
        assertThat(eligibility.isEligible(null)).isFalse();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private void assertRejected(StationMeasurement measurement, ErrorCode expected) {
        assertThat(eligibility.isEligible(measurement)).isFalse();
        assertThatThrownBy(() -> eligibility.requireEligible(measurement))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private StationMeasurement measurement(StationMeasurementStatus status, Double cargoHeight,
            String tippingLevel, Double overhangRatio) {
        StationMeasurement m = new StationMeasurement();
        m.setMeasurementId("M-1");
        m.setSessionId("session-1");
        m.setStatus(status);
        m.setCargoHeight(cargoHeight);
        m.setTippingLevel(tippingLevel);
        m.setOverhangRatio(overhangRatio);
        return m;
    }
}
