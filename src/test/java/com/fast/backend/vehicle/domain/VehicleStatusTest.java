package com.fast.backend.vehicle.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleStatusTest {

    @Test
    void fromRaw_knownValue_returnsMatchingEnum() {
        assertThat(VehicleStatus.fromRaw("ACTIVE")).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void fromRaw_lowercaseValue_isCaseInsensitive() {
        assertThat(VehicleStatus.fromRaw("idle")).isEqualTo(VehicleStatus.IDLE);
    }

    @Test
    void fromRaw_nullOrBlank_returnsUnknown() {
        assertThat(VehicleStatus.fromRaw(null)).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(VehicleStatus.fromRaw("  ")).isEqualTo(VehicleStatus.UNKNOWN);
    }

    @Test
    void fromRaw_lowercaseActive_returnsActive() {
        // prompt25.md 1.1장: 주행 중 상태값은 ACTIVE로 통일 확정.
        assertThat(VehicleStatus.fromRaw("active")).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void fromRaw_moving_isNormalizedToActive() {
        // MOVING은 ROS2 내부 표기다 — prompt16.md 5장에서는 "확정 전" 후보값이었지만, prompt25.md
        // 1.1장에서 ACTIVE로 통일 확정됐다. 호환을 위해 MOVING이 와도 ACTIVE로 정규화한다.
        assertThat(VehicleStatus.fromRaw("MOVING")).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void fromRaw_lowercaseMoving_isNormalizedToActive() {
        assertThat(VehicleStatus.fromRaw("moving")).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void fromRaw_unknownCandidate_returnsUnknownWithoutThrowing() {
        assertThat(VehicleStatus.fromRaw("LOADING")).isEqualTo(VehicleStatus.UNKNOWN);
    }

    @Test
    void fromRaw_existingStatusValues_stillMapCorrectly() {
        // 기존 상태값 회귀 테스트(prompt25.md 9.1장) — MOVING 매핑 추가가 다른 값에 영향을 주지 않는지 확인.
        assertThat(VehicleStatus.fromRaw("IDLE")).isEqualTo(VehicleStatus.IDLE);
        assertThat(VehicleStatus.fromRaw("ERROR")).isEqualTo(VehicleStatus.ERROR);
        assertThat(VehicleStatus.fromRaw("OFFLINE")).isEqualTo(VehicleStatus.OFFLINE);
        assertThat(VehicleStatus.fromRaw("UNKNOWN")).isEqualTo(VehicleStatus.UNKNOWN);
    }
}
