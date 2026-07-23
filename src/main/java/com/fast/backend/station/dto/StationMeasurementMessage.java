package com.fast.backend.station.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 측정 스테이션 → 백엔드 측정 결과 페이로드 v1.0 수신 DTO(prompt16.md 3단계, MR !36, FR-101-5).
 * {@code fast/station/{station_id}/measurement} 토픽으로 수신되며, 기존 {@code AiCargoAnalysisMessage}와
 * 절대 혼합하지 않는 별도 스테이션 전용 DTO다(원칙 2·3·5번).
 *
 * <p><b>snake_case 수신</b>: 이 프로젝트의 공유 ObjectMapper는 camelCase 기본이므로, 스테이션 규격의
 * snake_case 키를 정확히 받기 위해 필드마다 {@link JsonProperty}를 명시한다(원칙 4번). 이 방식은 전역
 * naming 전략을 바꾸지 않아 기존 cargo/detected 등 다른 도메인 DTO에 영향을 주지 않는다.
 *
 * <p><b>measuredAt</b>: {@link OffsetDateTime}으로 받아 {@code +09:00} 오프셋을 손실 없이 수신한다
 * (정책 9번). 저장은 UTC 변환 시각 + offset_minutes로 분리 보존한다({@code StationMeasurementAdapter}).
 */
public record StationMeasurementMessage(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("measurement_id") String measurementId,
        @JsonProperty("station_id") String stationId,
        @JsonProperty("measured_at") OffsetDateTime measuredAt,
        @JsonProperty("status") String status,
        @JsonProperty("detection") Detection detection,
        @JsonProperty("distance") Distance distance,
        @JsonProperty("dimensions") Dimensions dimensions,
        @JsonProperty("load_balance") LoadBalance loadBalance
) {

    public record Detection(
            @JsonProperty("box_count") Integer boxCount,
            @JsonProperty("boxes") List<DetectedBox> boxes,
            @JsonProperty("pallet") PalletDetection pallet
    ) {
    }

    public record DetectedBox(
            @JsonProperty("bbox_px") List<Integer> bboxPx,
            @JsonProperty("score") Double score
    ) {
    }

    public record PalletDetection(
            @JsonProperty("bbox_px") List<Integer> bboxPx,
            @JsonProperty("score") Double score
    ) {
    }

    public record Distance(
            @JsonProperty("front_cm") Double frontCm,
            @JsonProperty("std_cm") Double stdCm,
            @JsonProperty("frames_used") Integer framesUsed
    ) {
    }

    public record Dimensions(
            @JsonProperty("height_cm") Double heightCm,
            @JsonProperty("width_cm") Double widthCm,
            @JsonProperty("depth_cm") Double depthCm,
            @JsonProperty("miniature_scale") Integer miniatureScale,
            @JsonProperty("miniature_height_mm") Double miniatureHeightMm,
            @JsonProperty("miniature_width_mm") Double miniatureWidthMm
    ) {
    }

    public record LoadBalance(
            @JsonProperty("eccentric") Boolean eccentric,
            @JsonProperty("direction") List<String> direction,
            @JsonProperty("ratio_x") Double ratioX,
            @JsonProperty("ratio_y") Double ratioY,
            @JsonProperty("magnitude") Double magnitude,
            @JsonProperty("threshold") Double threshold,
            @JsonProperty("message") String message
    ) {
    }
}
