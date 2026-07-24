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

    /**
     * 확정 규격(prompt32.md 1장 3번)으로 공통 {@code VehicleStatus}가 10종이 되면서 Isaac 7종이
     * <b>전부 1:1로 대응</b>한다 — 더 이상 MOVING/LIFTING/LOADING이 ACTIVE로, ESTOP이 ERROR로
     * 흡수되지 않는다. 이 테스트가 "정보 손실 없음"을 코드로 고정한다.
     */
    @Test
    void toCommonVehicleStatusRaw_matchesDocumentedMappingTable() {
        assertThat(IsaacForkliftStatus.IDLE.toCommonVehicleStatusRaw()).isEqualTo("IDLE");
        assertThat(IsaacForkliftStatus.MOVING.toCommonVehicleStatusRaw()).isEqualTo("MOVING");
        assertThat(IsaacForkliftStatus.LIFTING.toCommonVehicleStatusRaw()).isEqualTo("LIFTING");
        assertThat(IsaacForkliftStatus.LOADING.toCommonVehicleStatusRaw()).isEqualTo("LOADING");
        assertThat(IsaacForkliftStatus.ERROR.toCommonVehicleStatusRaw()).isEqualTo("ERROR");
        assertThat(IsaacForkliftStatus.ESTOP.toCommonVehicleStatusRaw()).isEqualTo("ESTOP");
        assertThat(IsaacForkliftStatus.OFFLINE.toCommonVehicleStatusRaw()).isEqualTo("OFFLINE");
    }

    /**
     * 매핑 결과가 공통 enum에 실제로 존재하는 값인지 확인한다 — 이름만 같고 enum에 없으면
     * {@code VehicleStatus.fromRaw()}가 조용히 UNKNOWN으로 떨어뜨려 정보 손실이 되돌아온다.
     */
    @Test
    void toCommonVehicleStatusRaw_everyValueResolvesToARealVehicleStatus() {
        for (IsaacForkliftStatus status : IsaacForkliftStatus.values()) {
            assertThat(com.fast.backend.vehicle.domain.VehicleStatus.fromRaw(status.toCommonVehicleStatusRaw()))
                    .isNotEqualTo(com.fast.backend.vehicle.domain.VehicleStatus.UNKNOWN);
        }
    }
}
