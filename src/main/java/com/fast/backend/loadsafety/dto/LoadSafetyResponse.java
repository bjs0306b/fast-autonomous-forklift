package com.fast.backend.loadsafety.dto;

import java.time.OffsetDateTime;

/**
 * 적재 안전 상태 응답(prompt63.md 3장 2·3번).
 *
 * <p><b>REST 응답과 WebSocket {@code data} payload가 같은 레코드다</b> — 의도적이다. 프론트는 화면 진입
 * 시 REST로 최신값을 받고 이후 WebSocket으로 갱신하는데, 두 경로의 구조가 다르면 정규화 함수를 두 벌
 * 유지해야 하고 "REST에서는 보이던 필드가 실시간 갱신 후 사라지는" 종류의 버그가 생긴다. 기존
 * {@code VehicleStatusResponse}도 REST와 {@code VEHICLE_STATUS_UPDATED} 양쪽에 같은 타입을 쓴다.
 *
 * <p>{@code riskLevel}/{@code source}는 enum이 아니라 <b>문자열</b>로 내보낸다 — 프론트가 union 타입으로
 * 받되 모르는 값이 와도 화면이 깨지지 않게 하기 위해서다(백엔드는 이미 UNKNOWN으로 정규화했다).
 *
 * <p>{@code receivedAt}은 백엔드 수신 시각이다. 프론트가 "데이터가 오래됐는지"를 판단할 때
 * {@code detectedAt}(센서 측정 시각)과 함께 쓴다(3장 7번 "데이터 미수신·오래된 데이터 표시").
 */
public record LoadSafetyResponse(
        String vehicleId,
        String cargoId,
        Double forkHeight,
        Double cargoHeight,
        Double roll,
        Double pitch,
        Double loadOffsetX,
        Double loadOffsetY,
        String riskLevel,
        String riskCode,
        String message,
        String source,
        OffsetDateTime detectedAt,
        OffsetDateTime receivedAt
) {
}
