package com.fast.backend.forklift.dto;

import java.time.LocalDateTime;

/**
 * {@code forklift/{vehicleId}/location} 토픽으로 수신되는 ROS2 차량 위치·방향·속도 메시지(prompt24.md 1장).
 *
 * <p>이전 "Mock 통신 규격"(평면 {@code x/y/direction/speed/timestamp})을 ROS2 실제 규격으로 교체했다.
 * {@code vehicleId}/{@code position.x}/{@code position.y}/{@code messageAt}은 필수, 나머지는 ROS2가
 * 아직 제공하지 않을 수 있어 전부 nullable로 둔다(prompt24.md 4장). 값 검증은 이 레코드가 아니라
 * {@link com.fast.backend.forklift.service.ForkliftLocationService}가 수행한다 — MQTT 페이로드에는
 * REST처럼 {@code @Valid}를 걸 수 있는 경계가 없기 때문이다.
 */
public record ForkliftLocationMessage(
        String vehicleId,
        String status,
        Position position,
        Double heading,
        Quaternion quaternion,
        Double speed,
        LocalDateTime messageAt
) {

    public record Position(Double x, Double y, String frameId) {
    }

    public record Quaternion(Double x, Double y, Double z, Double w) {
    }
}
