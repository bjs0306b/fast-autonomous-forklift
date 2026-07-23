package com.fast.backend.vehicle.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.LocalDateTime;

/**
 * 차량 상태 부분 응답. 목록/상세 조회, 상태 갱신 API 응답에서 공통으로 재사용한다.
 *
 * <p>battery/positionX/positionY/heading/speed/messageAt/receivedAt은 상태가 한 번도 수신되지 않았거나
 * 해당 필드가 아직 제공되지 않는 경우 모두 null일 수 있다(prompt16.md 6장, 13장). 다만 이 응답 객체
 * 자체(래퍼)는 항상 non-null로 내려준다 — 13장 "상태 없음"을 UNKNOWN/null 중 어떻게 표현할지에 대한
 * 분석과 결론은 {@code answer15.md} 13장에 문서화했다.
 */
public record VehicleStatusResponse(
        VehicleStatus status,
        Integer battery,
        Double positionX,
        Double positionY,
        Double heading,
        Double speed,
        LocalDateTime messageAt,
        LocalDateTime receivedAt
) {

    public static VehicleStatusResponse unknown() {
        return new VehicleStatusResponse(VehicleStatus.UNKNOWN, null, null, null, null, null, null, null);
    }
}
