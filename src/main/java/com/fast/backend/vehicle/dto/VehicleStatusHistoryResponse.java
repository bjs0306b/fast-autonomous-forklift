package com.fast.backend.vehicle.dto;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;

import java.time.OffsetDateTime;

/** 차량 상태 이력 한 건. 시각은 통신 규격대로 {@code +09:00} {@link OffsetDateTime}으로 내보낸다. */
public record VehicleStatusHistoryResponse(
        Long historyId,
        VehicleStatus status,
        Integer battery,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {
    public static VehicleStatusHistoryResponse from(VehicleStatusHistory history) {
        return new VehicleStatusHistoryResponse(
                history.getHistoryId(), history.getStatus(), history.getBattery(),
                CommunicationTime.toOffset(history.getMessageAt()),
                CommunicationTime.toOffset(history.getReceivedAt()));
    }
}
