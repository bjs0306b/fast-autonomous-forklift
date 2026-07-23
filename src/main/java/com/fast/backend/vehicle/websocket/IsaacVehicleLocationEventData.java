package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;

/**
 * {@code VEHICLE_LOCATION_UPDATED} 이벤트의 {@code data} payload — Isaac Sim 위치 메시지 전용
 * (prompt28.md 3장·12장). 기존 {@link VehicleLocationEventData}(ROS2 실물 규격: 중첩 position,
 * heading은 degree)와 필드 구조·단위가 달라 별도 클래스로 분리했다 — 같은 클래스를 재사용하면
 * {@code heading}이 degree인지 rad인지 문서상 모순이 생긴다(answer28.md 4장 근거).
 *
 * <p>단위는 합의 규격 그대로 유지한다: {@code x}/{@code y}는 m, {@code direction}은 rad, {@code speed}는
 * m/s. 정규화·변환을 하지 않는다.
 */
public record IsaacVehicleLocationEventData(
        String forkliftId,
        Double x,
        Double y,
        Double direction,
        Double speed,
        LocalDateTime timestamp,
        LocalDateTime receivedAt
) {
}
