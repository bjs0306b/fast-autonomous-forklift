package com.fast.backend.vehicle.websocket;

import java.time.OffsetDateTime;

/**
 * {@code VEHICLE_LOCATION_UPDATED} 이벤트의 {@code data} payload — Isaac Sim 위치 메시지 전용
 * (prompt28.md 3장·12장).
 *
 * <p>기존 {@link VehicleLocationEventData}(ROS2 규격: 중첩 {@code position}, {@code quaternion})와
 * 필드 <b>구조</b>가 달라 별도 클래스를 유지한다. 다만 prompt32.md 1장 5번 확정으로 <b>방향 필드의
 * 이름과 단위는 통일됐다</b> — 이전의 {@code direction}(rad)이 {@code heading}(degree, [0,360))으로
 * 바뀌었다. 이제 프론트는 ROS2/Isaac 어느 쪽 payload를 받든 {@code heading}을 같은 의미로 읽을 수 있다.
 *
 * <p>단위: {@code x}/{@code y}는 m(ROS2와 <b>동일 원점</b> 가정), {@code heading}은 degree,
 * {@code speed}는 m/s. 시각은 {@code +09:00} {@link OffsetDateTime}(1장 6번).
 */
public record IsaacVehicleLocationEventData(
        String forkliftId,
        Double x,
        Double y,
        Double heading,
        Double speed,
        OffsetDateTime timestamp,
        OffsetDateTime receivedAt
) {
}
