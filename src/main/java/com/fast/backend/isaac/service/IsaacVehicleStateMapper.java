package com.fast.backend.isaac.service;

import com.fast.backend.vehicle.domain.VehicleStatus;

/** Isaac 상태 문자열을 관제 공통 상태로 변환한다. 알 수 없는 값은 UNKNOWN으로 남긴다. */
final class IsaacVehicleStateMapper {

    private IsaacVehicleStateMapper() {
    }

    static VehicleStatus fromRaw(String rawState) {
        if (rawState == null) {
            return VehicleStatus.UNKNOWN;
        }
        return switch (rawState.trim().toUpperCase()) {
            case "LOWERING" -> VehicleStatus.LIFTING;
            case "ESTOPPED" -> VehicleStatus.ESTOP;
            default -> VehicleStatus.fromRaw(rawState);
        };
    }
}
