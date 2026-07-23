package com.fast.backend.isaac.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isaac 상태 어휘 → 공통 VehicleStatus 매핑표(prompt28.md 11장)를 코드로 고정한다.
 */
class IsaacForkliftStatusTest {

    @Test
    void fromRaw_knownValues_mapCorrectly() {
        assertThat(IsaacForkliftStatus.fromRaw("IDLE")).contains(IsaacForkliftStatus.IDLE);
        assertThat(IsaacForkliftStatus.fromRaw("moving")).contains(IsaacForkliftStatus.MOVING);
        assertThat(IsaacForkliftStatus.fromRaw("ESTOP")).contains(IsaacForkliftStatus.ESTOP);
    }

    @Test
    void fromRaw_unknownValue_returnsEmpty() {
        assertThat(IsaacForkliftStatus.fromRaw("PARKED")).isEmpty();
    }

    @Test
    void fromRaw_nullOrBlank_returnsEmpty() {
        assertThat(IsaacForkliftStatus.fromRaw(null)).isEmpty();
        assertThat(IsaacForkliftStatus.fromRaw(" ")).isEmpty();
    }

    @Test
    void toCommonVehicleStatusRaw_matchesDocumentedMappingTable() {
        assertThat(IsaacForkliftStatus.IDLE.toCommonVehicleStatusRaw()).isEqualTo("IDLE");
        assertThat(IsaacForkliftStatus.MOVING.toCommonVehicleStatusRaw()).isEqualTo("ACTIVE");
        assertThat(IsaacForkliftStatus.LIFTING.toCommonVehicleStatusRaw()).isEqualTo("ACTIVE");
        assertThat(IsaacForkliftStatus.LOADING.toCommonVehicleStatusRaw()).isEqualTo("ACTIVE");
        assertThat(IsaacForkliftStatus.ERROR.toCommonVehicleStatusRaw()).isEqualTo("ERROR");
        assertThat(IsaacForkliftStatus.ESTOP.toCommonVehicleStatusRaw()).isEqualTo("ERROR");
        assertThat(IsaacForkliftStatus.OFFLINE.toCommonVehicleStatusRaw()).isEqualTo("OFFLINE");
    }
}
