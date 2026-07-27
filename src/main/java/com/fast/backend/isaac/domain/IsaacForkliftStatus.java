package com.fast.backend.isaac.domain;

import java.util.Optional;

/**
 * Isaac Sim이 보내는 지게차 상태 어휘 7종(prompt28.md 4장).
 *
 * <p><b>정보 손실이 해소됐다(prompt32.md 1장 3번)</b>: 공통
 * {@link com.fast.backend.vehicle.domain.VehicleStatus}가 10종으로 확장되면서 이 enum의 7개 값이
 * <b>전부 1:1로 대응</b>된다. 과거에는 {@code MOVING/LIFTING/LOADING → ACTIVE},
 * {@code ESTOP → ERROR}로 뭉뚱그려지며 의미가 사라졌지만, 이제 {@link #toCommonVehicleStatusRaw()}가
 * 같은 이름의 공통 상태를 그대로 돌려준다.
 *
 * <p>원본 Isaac 상태 문자열을 WebSocket 이벤트({@code IsaacVehicleStatusEventData})에 함께 실어 보내는
 * 동작은 그대로 유지한다 — forkHeight/hasCargo/cargoId/footprint 같은 Isaac 고유 상세 정보를 담는
 * 통로이기 때문이다(이 값들은 prompt32.md 1장 4번 확정에 따라 이제 DB에도 저장된다).
 */
public enum IsaacForkliftStatus {
    IDLE,
    MOVING,
    LIFTING,
    LOADING,
    ERROR,
    ESTOP,
    OFFLINE;

    public static Optional<IsaacForkliftStatus> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(IsaacForkliftStatus.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 공통 {@code VehicleStatus.fromRaw()}가 그대로 받을 수 있는 원시 문자열로 변환한다.
     * 확정 enum 10종이 이 7종을 전부 포함하므로 <b>이름을 그대로 반환</b>하며, 어떤 값도 다른 상태로
     * 흡수되지 않는다.
     */
    public String toCommonVehicleStatusRaw() {
        return name();
    }
}
