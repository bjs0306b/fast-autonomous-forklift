package com.fast.backend.traffic.domain;

/** 교통 관제 이벤트 종류 (FR-502-1a). */
public enum TrafficEventType {

    /** 관제가 차량을 세웠다(STOP 발행). */
    HOLD,

    /** 조건이 풀려 주행을 재개시켰다(RESUME 발행). */
    RELEASE
}
