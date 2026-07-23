package com.fast.backend.station.dto;

import com.fast.backend.station.domain.StationDirection;
import com.fast.backend.station.domain.StationMeasurementStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 측정 스테이션 측정 결과 조회/브로드캐스트 응답 DTO(prompt16.md 9·10단계). 응답 JSON은 camelCase
 * (프로젝트 기본 naming) — 수신 DTO({@link StationMeasurementMessage})만 snake_case이며 응답은 다른
 * 조회 API/이벤트와 일관되게 camelCase를 쓴다. {@code measuredAt}은 복원된 {@link OffsetDateTime}이라
 * 오프셋({@code +09:00})이 그대로 응답된다.
 */
public record StationMeasurementResponse(
        String measurementId,
        String stationId,
        String schemaVersion,
        OffsetDateTime measuredAt,
        StationMeasurementStatus status,
        Detection detection,
        Distance distance,
        Dimensions dimensions,
        LoadBalance loadBalance,
        LocalDateTime receivedAt
) {

    public record Detection(Integer boxCount, List<DetectedBox> boxes, Pallet pallet) {
    }

    public record DetectedBox(List<Integer> bboxPx, Double score) {
    }

    public record Pallet(List<Integer> bboxPx, Double score) {
    }

    public record Distance(Double frontCm, Double stdCm, Integer framesUsed) {
    }

    public record Dimensions(
            Double heightCm, Double widthCm, Double depthCm,
            Integer miniatureScale, Double miniatureHeightMm, Double miniatureWidthMm) {
    }

    public record LoadBalance(
            Boolean eccentric, List<StationDirection> direction,
            Double ratioX, Double ratioY, Double magnitude, Double threshold, String message) {
    }
}
