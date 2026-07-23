package com.fast.backend.isaac.domain;

import java.util.Optional;

/**
 * Isaac Sim이 보내는 지게차 상태 어휘 7종(prompt28.md 4장). {@code VehicleStatus}(공통 관제 상태,
 * UNKNOWN/IDLE/ACTIVE/ERROR/OFFLINE 5종)와 1:1로 대응하지 않는다 — {@link #toCommonVehicleStatusRaw()}로
 * 매핑할 때 {@code LIFTING}/{@code LOADING}의 "작업 중" 구분과 {@code ESTOP}의 "비상 정지" 의미가
 * {@code ACTIVE}/{@code ERROR}로 뭉뚱그려지며 손실된다(11장 "정보 손실을 기록한다" 조건).
 *
 * <p>그래서 원본 Isaac 상태 문자열은 이 매핑과 별개로 WebSocket 이벤트({@code IsaacVehicleStatusEventData})에
 * 항상 그대로 함께 실어 보낸다 — "방식 B: Isaac 전용 상태 enum을 두고 관제용 공통 상태로 매핑"을
 * 선택한 이유는 answer28.md 4장(구현 설계 결정)에 문서화했다.
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
     * 기존 {@code VehicleStatus.fromRaw()}가 그대로 받을 수 있는 원시 문자열로 정규화한다.
     * VehicleStatus에 없는 값(LIFTING/LOADING/ESTOP)은 가장 가까운 공통 상태로 매핑해 정보 손실을
     * 최소화한다 — 이 매핑 없이 원본 문자열을 그대로 넘기면 VehicleStatus.fromRaw()가 전부 UNKNOWN으로
     * 떨어뜨려 "작업 중"이라는 유용한 신호까지 사라진다.
     */
    public String toCommonVehicleStatusRaw() {
        return switch (this) {
            case IDLE -> "IDLE";
            case MOVING, LIFTING, LOADING -> "ACTIVE";
            case ERROR, ESTOP -> "ERROR";
            case OFFLINE -> "OFFLINE";
        };
    }
}
