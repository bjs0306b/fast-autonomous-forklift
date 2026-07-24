package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;

/**
 * 차량 상태 부분 응답. 목록/상세 조회, 상태 갱신 API 응답, 차량 상태 WebSocket 이벤트의 {@code data}에서
 * 공통으로 재사용한다.
 *
 * <p>battery/positionX/positionY/heading/speed/messageAt/receivedAt과 Isaac 확장 필드는 상태가 한 번도
 * 수신되지 않았거나 해당 필드가 제공되지 않는 경우 모두 null일 수 있다(prompt16.md 6장, 13장). 다만 이
 * 응답 객체 자체(래퍼)는 항상 non-null로 내려준다 — "상태 없음"도 {@link #unknown()}으로 같은 모양을
 * 유지한다(answer15.md 13장).
 *
 * <p>{@code heading}은 degree([0,360))이고, 시각 필드는 {@code +09:00} {@link OffsetDateTime}이다
 * (prompt32.md 1장 5번·6번 확정).
 */
public record VehicleStatusResponse(
        VehicleStatus status,
        Integer battery,
        Double positionX,
        Double positionY,
        Double heading,
        Double speed,
        Double forkHeight,
        Boolean hasCargo,
        String cargoId,
        Double footprintLength,
        Double footprintWidth,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {

    public static VehicleStatusResponse unknown() {
        return new VehicleStatusResponse(
                VehicleStatus.UNKNOWN, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
