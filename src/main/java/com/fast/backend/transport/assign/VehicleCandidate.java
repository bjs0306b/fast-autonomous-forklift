package com.fast.backend.transport.assign;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.LocalDateTime;

/**
 * 자동 배정 후보 차량의 읽기 모델(prompt46.md 12장). 영속 계층(추후 구현)이 vehicle + vehicle_current_status
 * + 활성 작업 여부를 조합해 만든다 — 선택 로직을 특정 조회 구조에 묶지 않기 위해 순수 레코드로 둔다.
 *
 * <p><b>주의(prompt44.md 확인)</b>: 현재 위치({@code positionX}/{@code positionY})는 MQTT 위치 메시지가
 * {@code vehicle_current_status}에 저장되지 않아 실제로는 대부분 null이다. 그래서 거리 기반 자동 배정은
 * 위치 저장이 확정된 뒤에만 실제 활성화할 수 있다({@link NearestIdleVehicleSelector} 참고).
 *
 * <ul>
 *   <li>{@code idleSince} — IDLE 상태가 된 시각(동일 거리 tie-break 2순위: 먼저 IDLE이 된 차량)</li>
 *   <li>{@code statusTimestamp} — 마지막 상태 메시지 시각(tie-break 3순위: 최신 데이터 우선)</li>
 * </ul>
 */
public record VehicleCandidate(
        String vehicleId,
        boolean online,
        VehicleStatus status,
        boolean hasActiveTask,
        Double positionX,
        Double positionY,
        LocalDateTime idleSince,
        LocalDateTime statusTimestamp
) {
}
