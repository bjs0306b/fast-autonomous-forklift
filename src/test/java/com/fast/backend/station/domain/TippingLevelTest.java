package com.fast.backend.station.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 전복 등급 대소문자 정규화(prompt95.md 9장).
 *
 * <p>비전은 {@code "safe"}, DB CHECK 는 {@code 'SAFE'} — 이 enum 이 그 간극을 메운다. 알 수 없는 값을
 * null 로 눙치지 않고 empty 를 돌려주는 것이 핵심이다(호출자가 오류로 처리해야 한다).
 */
class TippingLevelTest {

    @Test
    void fromRaw_normalizesLowercaseAndUppercase() {
        assertThat(TippingLevel.fromRaw("safe")).contains(TippingLevel.SAFE);
        assertThat(TippingLevel.fromRaw("SAFE")).contains(TippingLevel.SAFE);
        assertThat(TippingLevel.fromRaw(" warning ")).contains(TippingLevel.WARNING);
        assertThat(TippingLevel.fromRaw("Danger")).contains(TippingLevel.DANGER);
    }

    @Test
    void fromRaw_isEmptyForUnknownBlankAndNull() {
        assertThat(TippingLevel.fromRaw("critical")).isEmpty();
        assertThat(TippingLevel.fromRaw("")).isEmpty();
        assertThat(TippingLevel.fromRaw("   ")).isEmpty();
        assertThat(TippingLevel.fromRaw(null)).isEmpty();
    }

    @Test
    void rawValue_isUppercase_soResponseMatchesStoredValue() {
        assertThat(TippingLevel.SAFE.rawValue()).isEqualTo("SAFE");
        assertThat(TippingLevel.DANGER.rawValue()).isEqualTo("DANGER");
    }

    @Test
    void statusFromRaw_supportsDimensionsOnly() {
        assertThat(StationMeasurementStatus.fromRaw("dimensions_only"))
                .contains(StationMeasurementStatus.DIMENSIONS_ONLY);
        assertThat(StationMeasurementStatus.DIMENSIONS_ONLY.rawValue()).isEqualTo("dimensions_only");
    }
}
