package com.fast.backend.vehicle.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;

/**
 * PUT /api/vehicles/{vehicleId}/status 요청 바디(검증용 임시 API, {@code VehicleStatusTestController} 전용).
 *
 * <p>{@code status}를 {@link com.fast.backend.vehicle.domain.VehicleStatus} enum이 아니라 일부러
 * 원시 문자열로 받는다. enum으로 바로 받으면 Jackson이 알 수 없는 값에서 즉시 400을 던져버리는데,
 * prompt16.md 11장 조건("알 수 없는 상태는 UNKNOWN으로 처리")은 요청 자체를 거부하지 말고 서비스
 * 레벨에서 안전하게 흡수하라는 뜻이기 때문이다. 실제 정규화는
 * {@link com.fast.backend.vehicle.service.VehicleStatusService}에서 수행한다.
 */
public record VehicleStatusUpdateRequest(
        @NotBlank String status,
        Double positionX,
        Double positionY,
        Double heading,
        Double speed,
        OffsetDateTime messageAt
) {
}
