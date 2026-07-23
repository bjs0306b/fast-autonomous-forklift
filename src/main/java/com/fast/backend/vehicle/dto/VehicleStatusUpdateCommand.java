package com.fast.backend.vehicle.dto;

import java.time.LocalDateTime;

/**
 * {@link com.fast.backend.vehicle.service.VehicleStatusService#updateCurrentStatus} 의 입력 커맨드.
 *
 * <p>이 클래스는 MQTT JSON이나 REST 요청 바디 어느 쪽에도 의존하지 않는다(prompt16.md 11장·18장 조건).
 * {@code VehicleStatusTestController}는 {@link VehicleStatusUpdateRequest}를 이 커맨드로 변환해서
 * 넘기고, 향후 MQTT 연동이 완료되면 {@code Ros2VehicleStatusAdapter}/{@code IsaacSimVehicleStatusAdapter}
 * (아직 미구현, 18장 참고)가 각자의 JSON을 이 커맨드로 변환해서 동일한 Service 메서드를 호출하게 된다.
 *
 * <p>{@code status}는 원시 문자열 그대로 받는다 — "상태값 정규화"(11장의 처리 순서)는 호출부가 아니라
 * Service 내부에서 {@link com.fast.backend.vehicle.domain.VehicleStatus#fromRaw(String)}로 수행해,
 * REST API든 미래의 MQTT Adapter든 항상 같은 규칙으로 안전하게 처리되도록 한다.
 */
public record VehicleStatusUpdateCommand(
        String status,
        Integer battery,
        Double positionX,
        Double positionY,
        Double heading,
        Double speed,
        LocalDateTime messageAt
) {
}
