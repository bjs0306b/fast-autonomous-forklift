package com.fast.backend.traffic.domain;

/**
 * 화물 한 개를 처리하는 한 주기의 단계 (F팀 규격 {@code backend-control-impl} §5).
 *
 * <pre>
 *   TO_BAY ──도착──▶ ALIGN_BAY ──정렬완료──▶ LOAD ──적재확인──▶ TO_EXIT
 *                                                                │
 *      ┌─────────────────────────────────────────────────────────┘
 *      ▼
 *   TO_RACK ──도착──▶ RACK ──적재완료──▶ (주기+1) TO_BAY
 * </pre>
 *
 * <p><b>시간만으로 넘어가지 않는다.</b> 규격이 명시한 실제 사고다 — 적재에 실패한 차가
 * {@code loaded} 를 확인하지 않고 다음 단계로 넘어가면 <b>빈 포크로 랙까지 가서 아무것도
 * 못 놓고 계속 순환</b>한다. 그래서 각 단계는 차량이 보고한 값({@code loaded}, {@code state})
 * 을 확인하고, 시간 제한은 <b>무한 대기를 막는 안전장치</b>로만 쓴다.
 */
public enum CyclePhase {

    /** 순환로를 돌아 입고 바이로 이동. 바이 반경 안에 들어오면 다음. */
    TO_BAY,

    /** 바이 정면 정렬({@code cargo ← align_bay}). 차량이 스스로 수행한다. */
    ALIGN_BAY,

    /** 화물 받기({@code cargo ← height}). {@code loaded == true} 여야 넘어간다. */
    LOAD,

    /** 바이 탈출. 여기서 바이 예약을 반납한다. */
    TO_EXIT,

    /** 배정된 랙 접근점으로 이동. */
    TO_RACK,

    /** 랙 적재({@code task ← PLACE_RACK}). {@code loaded == false} 여야 주기가 끝난다. */
    RACK,

    /**
     * 랙이 다 차서 시작 위치로 돌아가는 중.
     *
     * <p><b>주기 고리 밖이다.</b> {@code RACK → TO_BAY} 로 이어지는 순환과 달리 여기서는
     * 되돌아오지 않는다 — 놓을 자리가 없어서 복귀하는 것이므로.
     */
    RETURNING,

    /** 시작 위치에 도착해 멈춘 상태. 더 이상 목표를 보내지 않는다. */
    PARKED;

    /**
     * 지금 <b>제자리에서 작업 중</b>인 단계인가.
     *
     * <p>이 단계에서는 목적지를 새로 주지 않는다 — 정렬·적재 도중에 이동 목표가 날아오면
     * 차량이 작업을 버리고 출발한다.
     */
    public boolean isWorking() {
        return this == ALIGN_BAY || this == LOAD || this == RACK;
    }

    /** 지금 입고 바이를 점유(또는 점유 예정)하는 단계인가. 규칙 3(바이 예약)의 대상. */
    public boolean occupiesBay() {
        return this == TO_BAY || this == ALIGN_BAY || this == LOAD;
    }

    /** 복귀 계열인가 — 주기를 더 돌지 않는 단계. */
    public boolean isHomebound() {
        return this == RETURNING || this == PARKED;
    }

    /**
     * 주기의 다음 단계. {@link #RACK} 다음은 새 주기의 {@link #TO_BAY}.
     *
     * <p>{@link #RETURNING} 다음은 {@link #PARKED} 이고, {@code PARKED} 는 자기 자신이다 —
     * 복귀는 한 번 들어가면 나오지 않는다. 랙이 다시 비면 그때는 운행을 새로 시작하는 것이지
     * 멈춰 있던 주기를 잇는 것이 아니다(그 사이 무슨 일이 있었는지 알 수 없다).
     */
    public CyclePhase next() {
        return switch (this) {
            case TO_BAY -> ALIGN_BAY;
            case ALIGN_BAY -> LOAD;
            case LOAD -> TO_EXIT;
            case TO_EXIT -> TO_RACK;
            case TO_RACK -> RACK;
            case RACK -> TO_BAY;
            case RETURNING -> PARKED;
            case PARKED -> PARKED;
        };
    }
}
