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
    void fromRaw_moving_isPreservedAsMoving() {
        // prompt32.md 1장 3번 확정: MOVING은 더 이상 ACTIVE로 흡수되지 않고 독립 상태로 보존된다.
        assertThat(VehicleStatus.fromRaw("MOVING")).isEqualTo(VehicleStatus.MOVING);
    }

    @Test
    void fromRaw_lowercaseMoving_isPreservedAsMoving() {
        assertThat(VehicleStatus.fromRaw("moving")).isEqualTo(VehicleStatus.MOVING);
    }

    @Test
    void fromRaw_unknownCandidate_returnsUnknownWithoutThrowing() {
        // LOADING/UNLOADING/ESTOP은 확정 enum에 포함됐으므로, 실제로 정의되지 않은 값으로 검증한다.
        assertThat(VehicleStatus.fromRaw("PARKED")).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(VehicleStatus.fromRaw("CHARGING")).isEqualTo(VehicleStatus.UNKNOWN);
    }

    /**
     * 확정 enum 10종(prompt32.md 1장 3번)이 전부 자기 자신으로 정확히 매핑되는지 확인한다 —
     * 어떤 값도 다른 값으로 흡수되지 않는다는 것이 이번 확정의 핵심이다.
     */
    @Test
    void fromRaw_allConfirmedStatuses_mapToThemselves() {
        assertThat(VehicleStatus.values()).hasSize(10);
        for (VehicleStatus status : VehicleStatus.values()) {
            assertThat(VehicleStatus.fromRaw(status.name())).isEqualTo(status);
            assertThat(VehicleStatus.fromRaw(status.name().toLowerCase())).isEqualTo(status);
            assertThat(VehicleStatus.fromRaw("  " + status.name() + "  ")).isEqualTo(status);
        }
    }

    @Test
    void values_declarationOrderDrivesStatusCountResponseOrder() {
        // VehicleService#countByStatus가 values() 순서를 그대로 응답 항목 순서로 쓴다.
        assertThat(VehicleStatus.values()).containsExactly(
                VehicleStatus.UNKNOWN, VehicleStatus.IDLE, VehicleStatus.ACTIVE, VehicleStatus.MOVING,
                VehicleStatus.LIFTING, VehicleStatus.LOADING, VehicleStatus.UNLOADING, VehicleStatus.ESTOP,
                VehicleStatus.ERROR, VehicleStatus.OFFLINE);
    }
}
