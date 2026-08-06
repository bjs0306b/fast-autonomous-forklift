package com.fast.backend.traffic.domain;

/**
 * 관제가 차량을 세운 이유 (FR-502-1a).
 *
 * <p>FR 이 정한 정지 조건 두 가지와 1:1 로 대응한다. 문자열이 아니라 코드로 두는 이유는
 * <b>나중에 집계·조회가 되어야 하기 때문</b>이다 — "지난 시연에서 안전거리 때문에 몇 번 멈췄나"를
 * 사람이 로그를 읽어 세는 것과 {@code WHERE reason_code = 'SAFETY_DISTANCE'} 로 세는 것은 다르다.
 */
public enum TrafficHoldReason {

    /** 예상 차량 간 거리가 최소 안전거리보다 작다. */
    SAFETY_DISTANCE,

    /** 예상 경로가 다른 차량이 점유한 선반 작업 구역과 겹친다. */
    WORK_ZONE_OCCUPIED
}
