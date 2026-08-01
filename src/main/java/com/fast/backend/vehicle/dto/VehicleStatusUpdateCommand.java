package com.fast.backend.vehicle.dto;

import java.time.OffsetDateTime;

/** 정규화된 저주기 차량 상태 갱신값. 위치는 별도 MQTT 계약으로 처리한다. */
public record VehicleStatusUpdateCommand(
        String status,
        Integer battery,
        OffsetDateTime messageAt
) {
}
