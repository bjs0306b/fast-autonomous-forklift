package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;

/**
 * {@code VEHICLE_STATUS_UPDATED} 이벤트의 {@code data} payload — Isaac Sim 상태 메시지 전용
 * (prompt28.md 4장·5장·12장). {@code status}는 Isaac 원본 문자열(IDLE/MOVING/LIFTING/LOADING/ERROR/
 * ESTOP/OFFLINE)을 그대로 담는다 — {@code vehicle_current_status}에는 공통 {@code VehicleStatus}로
 * 정규화된 값만 저장되어 세부 정보가 손실되므로(이유는 {@code IsaacForkliftStatus} Javadoc 참고),
 * 이 이벤트가 원본 상세도를 보존하는 유일한 경로다.
 *
 * <p>LWT OFFLINE 메시지 처리 시({@code status == "OFFLINE"}이고 나머지 필드가 없는 경우)
 * {@code battery}/{@code forkHeight}/{@code hasCargo}/{@code cargoId}/{@code footprint}/{@code timestamp}는
 * 전부 null일 수 있다 — {@code receivedAt}만 항상 채워진다(5장 4번 "백엔드 수신 시각을 별도로 기록").
 */
public record IsaacVehicleStatusEventData(
        String forkliftId,
        String status,
        Integer battery,
        Double forkHeight,
        Boolean hasCargo,
        String cargoId,
        Footprint footprint,
        LocalDateTime timestamp,
        LocalDateTime receivedAt
) {

    public record Footprint(Double length, Double width) {
    }
}
