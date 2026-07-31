package com.fast.backend.vehicle.domain;

/**
 * 차량 데이터의 출처. 실물 지게차인지 Isaac Sim 시뮬레이션인지 구분한다.
 * vehicleId 명명 규칙(예: REAL-F01, SIM-F01)의 최종 확정과는 무관하게, 이 값 자체는
 * FR-501-1 범위에서 지금 확정해 구현한다(prompt16.md 4장).
 */
public enum VehicleSource {
    REAL,
    SIMULATION
}
