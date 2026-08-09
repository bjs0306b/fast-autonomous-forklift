package com.fast.backend.traffic.domain;

/**
 * 전체 운행 상태 (F팀 규격 {@code backend-control-impl} §0.6).
 *
 * <p><b>왜 차량별 상태와 따로 두는가.</b> 차량 하나하나의 {@code state}(IDLE/MOVING/…)는
 * 차량이 보고하는 값이라 "관제가 운행을 시작했는가"를 담을 수 없다. 세 대가 전부 IDLE 인
 * 상황은 "아직 시작 안 함"일 수도 있고 "전체 정지 중"일 수도 있는데, 화면은 이 둘에 서로
 * 다른 버튼을 보여야 한다.
 *
 * <p>전이는 {@code OperationService} 가 강제한다 — 여기서는 어떤 조작이 가능한지만 정의한다.
 *
 * <pre>
 *   IDLE ──start──▶ RUNNING ──stop───▶ PAUSED ──resume──▶ RUNNING
 *                      │                  │
 *                      └──estop──▶ ESTOPPED ──resume──▶ RUNNING
 *                                         │
 *                      IDLE ◀──reset──────┴── (어느 상태에서든)
 * </pre>
 */
public enum OperationState {

    /** 아직 시작하지 않음. 차량에 아무 목표도 보내지 않는다. */
    IDLE,

    /** 주행 중. 관제가 목표를 발행한다. */
    RUNNING,

    /** 전체 일시정지(HOLD). 합류 상태는 유지하므로 재개하면 이어서 돈다. */
    PAUSED,

    /** 비상정지. PAUSED 와 달리 사람이 확인한 뒤에만 재개한다. */
    ESTOPPED;

    /** 지금 관제가 목표를 발행해도 되는 상태인가. */
    public boolean isDriving() {
        return this == RUNNING;
    }

    /** 정지 계열(전체 HOLD 또는 비상정지)인가. */
    public boolean isStopped() {
        return this == PAUSED || this == ESTOPPED;
    }

    /** 운행 시작이 가능한가. 이미 돌고 있으면 시작 조작은 무의미하다. */
    public boolean canStart() {
        return this == IDLE;
    }

    /**
     * 재개가 가능한가.
     *
     * <p>{@code IDLE} 에서는 재개할 것이 없다 — 그 상태에서 재개를 허용하면 "시작"과 구별이
     * 없어져, 합류 순서(규칙 0)를 건너뛴 채 세 대가 한꺼번에 튀어나간다.
     */
    public boolean canResume() {
        return isStopped();
    }
}
