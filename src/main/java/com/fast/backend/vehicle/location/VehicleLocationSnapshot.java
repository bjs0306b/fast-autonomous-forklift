package com.fast.backend.vehicle.location;

import java.time.OffsetDateTime;

/**
 * 차량별 "최신 위치 1건" 스냅샷(prompt47.md 3-3, prompt50.md 6장). DB에 저장하지 않는 휘발성 값으로,
 * 대시보드 초기 조회(REST)와 실시간 표시에 쓴다. 좌표 m, heading degree, speed m/s.
 *
 * <p>{@code source}는 위치 메시지 출처 구분값이다 — ROS2 실물 경로는 {@code "REAL"}, Isaac Sim 경로는
 * {@code "SIM"}(prompt50.md 14장 "REAL/SIM source 구분"). 등록 차량의 {@code VehicleSource}와는 별개의
 * 표시용 태그다. {@code frameId}는 좌표계(map/odom, Isaac은 map 기본)다.
 */
public record VehicleLocationSnapshot(
        String vehicleId,
        String source,
        Double x,
        Double y,
        Double heading,
        Double speed,
        String frameId,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {
}
