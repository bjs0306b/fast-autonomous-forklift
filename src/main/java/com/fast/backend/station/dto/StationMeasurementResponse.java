package com.fast.backend.station.dto;

import com.fast.backend.station.domain.StationMeasurementStatus;

import java.time.LocalDateTime;

/**
 * 측정 결과 조회/브로드캐스트 응답 DTO — FR-202 세션 기반 구조(prompt85).
 *
 * <p><b>줄어든 응답이다.</b> 옛 응답은 detection/distance/dimensions/loadBalance 원본 상세를 그대로
 * 실어 보냈지만, FR-202 스키마는 그 값을 저장하지 않는다(8컬럼). 저장하지 않는 값을 응답에 담으면
 * REST 조회와 실시간 이벤트가 서로 다른 모양이 되므로, 양쪽 모두 <b>저장되는 것만</b> 내려보낸다.
 * 원본 상세가 필요하면 MQTT 원문을 소비해야 한다(별도 요구사항).
 */
public record StationMeasurementResponse(
        Long sequenceNo,
        String measurementId,
        String sessionId,
        String cargoId,
        StationMeasurementStatus status,
        Double cargoHeight,
        String tippingLevel,
        Double overhangRatio,
        LocalDateTime createdAt
) {
}
