package com.fast.backend.vehicle.dto;

import java.time.OffsetDateTime;

/**
 * {@link com.fast.backend.vehicle.service.VehicleStatusService#updateCurrentStatus} 의 입력 커맨드.
 *
 * <p>이 클래스는 MQTT JSON이나 REST 요청 바디 어느 쪽에도 의존하지 않는다(prompt16.md 11장·18장 조건).
 * {@code VehicleStatusTestController}(REST), {@code ForkliftStatusService}(ROS2 MQTT),
 * {@code IsaacForkliftStatusService}(Isaac MQTT)가 각자의 입력을 이 커맨드로 변환해 같은 Service
 * 메서드를 호출한다.
 *
 * <p>{@code status}는 원시 문자열 그대로 받는다 — 정규화는 호출부가 아니라 Service 내부에서
 * {@link com.fast.backend.vehicle.domain.VehicleStatus#fromRaw(String)}로 수행해, REST든 MQTT든 항상
 * 같은 규칙으로 안전하게 처리되도록 한다.
 *
 * <p>{@code messageAt}은 prompt32.md 1장 6번 확정에 따라 {@link OffsetDateTime}(+09:00)이다. DB 저장용
 * {@code LocalDateTime} 변환은 Service가
 * {@link com.fast.backend.common.time.CommunicationTime}으로 수행한다.
 *
 * <p><b>{@code isaacExtras}의 의미(prompt32.md 1장 4번)</b>
 * <ul>
 *   <li>{@code null} — 이 메시지는 Isaac 확장 정보를 <b>담고 있지 않다</b>(ROS2 상태 메시지, REST 테스트
 *       API). Service는 DB에 이미 저장된 Isaac 값을 <b>그대로 보존</b>한다.</li>
 *   <li>non-null — 이 메시지는 Isaac 상태 메시지다. 담긴 값으로 <b>덮어쓴다</b>(내부 필드가 null이면
 *       그 필드는 실제로 null로 갱신된다 — 예: 화물을 내려놓아 {@code cargoId}가 사라진 경우).</li>
 * </ul>
 * "필드가 null인 것"과 "메시지에 필드 자체가 없는 것"을 구분하기 위해 boolean 플래그가 아니라 중첩
 * 레코드의 null 여부로 표현했다.
 */
public record VehicleStatusUpdateCommand(
        String status,
        Integer battery,
        Double positionX,
        Double positionY,
        Double heading,
        Double speed,
        OffsetDateTime messageAt,
        IsaacExtras isaacExtras
) {

    /** Isaac 확장 정보를 담지 않는 호출자(ROS2 상태, REST 테스트 API)용 생성자. */
    public VehicleStatusUpdateCommand(
            String status,
            Integer battery,
            Double positionX,
            Double positionY,
            Double heading,
            Double speed,
            OffsetDateTime messageAt) {
        this(status, battery, positionX, positionY, heading, speed, messageAt, null);
    }

    /**
     * Isaac Sim 상태 메시지에만 존재하는 확장 필드 묶음
     * ({@code forkHeight}/{@code hasCargo}/{@code cargoId}/{@code footprint}).
     */
    public record IsaacExtras(
            Double forkHeight,
            Boolean hasCargo,
            String cargoId,
            Double footprintLength,
            Double footprintWidth
    ) {
    }
}
