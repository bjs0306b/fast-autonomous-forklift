package com.fast.backend.forklift.dto;

import java.time.OffsetDateTime;

/**
 * {@code forklift/{vehicleId}/location} 토픽으로 수신되는 ROS2 차량 위치·방향·속도 메시지(prompt24.md 1장).
 *
 * <p>{@code vehicleId}/{@code position.x}/{@code position.y}/{@code messageAt}은 필수, 나머지는 ROS2가
 * 제공하지 않을 수 있어 전부 nullable이다. 값 검증은 이 레코드가 아니라
 * {@link com.fast.backend.forklift.service.ForkliftLocationService}가 수행한다 — MQTT 페이로드에는
 * REST처럼 {@code @Valid}를 걸 수 있는 경계가 없기 때문이다.
 *
 * <p>이 메시지의 식별자 키는 {@code vehicleId}다(ROS2 <b>상태</b> 메시지는 {@code forkliftId}). 두 키가
 * 다른 것은 알려진 상태이며, prompt32.md 1장 2번 확정에 따라 <b>임의로 통일하지 않고 그대로 유지</b>한다
 * — {@code MqttMessageRouter}가 바로 이 키 차이로 ROS2/Isaac 경로를 판별하기 때문이다.
 *
 * <p><b>확정된 좌표·방향 규격(prompt32.md 1장 5번)</b>
 * <ul>
 *   <li>{@code position.x}/{@code position.y} — <b>m</b></li>
 *   <li>{@code position.frameId} — {@code map} 또는 {@code odom}만 허용. 생략 시 기본값 {@code map}.
 *       그 외 값은 메시지를 폐기한다(Service에서 경고 로그).</li>
 *   <li>{@code heading} — <b>degree</b>, [0,360)로 정규화해 중계</li>
 *   <li>{@code quaternion} — ROS2 원본값 그대로 중계(백엔드에서 계산하지 않음)</li>
 *   <li>{@code messageAt} — {@code +09:00} {@link OffsetDateTime}(1장 6번)</li>
 * </ul>
 */
public record ForkliftLocationMessage(
        String vehicleId,
        String status,
        Position position,
        Double heading,
        Quaternion quaternion,
        Double speed,
        OffsetDateTime messageAt
) {

    public record Position(Double x, Double y, String frameId) {
    }

    public record Quaternion(Double x, Double y, Double z, Double w) {
    }
}
