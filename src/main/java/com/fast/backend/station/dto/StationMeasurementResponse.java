package com.fast.backend.station.dto;

import com.fast.backend.station.domain.StationMeasurementStatus;
import java.time.LocalDateTime;

/** REST와 WebSocket 소비자에게 전달하는 측정 결과. */
public record StationMeasurementResponse(
        String measurementId,
        String sessionId,
        Long cargoId,
        StationMeasurementStatus status,
        Double cargoHeight,
        Double cargoWidth,
        /** 아래 {@code boxes} 픽셀 좌표의 기준 해상도. 화면 크기에 맞게 환산할 때 쓴다. */
        Integer frameWidth,
        Integer frameHeight,
        /** 검출 상자별 이미지 픽셀 좌표·점수. 관제 화면이 영상 위에 사각형을 그린다. */
        java.util.List<MeasurementBox> boxes,
        String tippingLevel,
        Double overhangRatio,
        boolean placementEligible,
        LocalDateTime createdAt
) {
}
