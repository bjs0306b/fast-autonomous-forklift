package com.fast.backend.station.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 측정 스테이션 v1.0 JSON(snake_case + measured_at +09:00)을 {@link StationMeasurementMessage}로 정확히
 * 역직렬화하는지 검증한다(prompt16.md 11단계 테스트 1). Spring 없이 순수 Jackson으로 검증하므로
 * JDK/Mockito와 무관하게 항상 실행된다. ObjectMapper는 프로젝트 런타임과 동일하게 JavaTimeModule을
 * 등록한 것으로 구성한다(Spring Boot 기본과 동일).
 */
class StationMeasurementJsonTest {

    // 프로덕션과 동일하게 오프셋을 보존하도록 구성한다(application.yml의
    // spring.jackson.deserialization.adjust-dates-to-context-time-zone=false와 동일 효과).
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

    private static final String FULL_JSON = """
            {
              "schema_version": "1.0",
              "measurement_id": "st1-20260722-130501-0007",
              "station_id": "station-1",
              "measured_at": "2026-07-22T13:05:01+09:00",
              "status": "ok",
              "detection": {
                "box_count": 1,
                "boxes": [ { "bbox_px": [412, 180, 350, 310], "score": 0.97 } ],
                "pallet": { "bbox_px": [380, 460, 520, 140], "score": 0.99 }
              },
              "distance": { "front_cm": 152.3, "std_cm": 0.42, "frames_used": 48 },
              "dimensions": {
                "height_cm": 30.2, "width_cm": 34.1, "depth_cm": null,
                "miniature_scale": 10, "miniature_height_mm": 30.2, "miniature_width_mm": 34.1
              },
              "load_balance": {
                "eccentric": true, "direction": ["right"],
                "ratio_x": 0.40, "ratio_y": 0.02, "magnitude": 0.40, "threshold": 0.3,
                "message": "오른쪽 편하중"
              }
            }
            """;

    @Test
    void deserialize_allSnakeCaseFields_areMappedCorrectly() throws Exception {
        StationMeasurementMessage m = objectMapper.readValue(FULL_JSON, StationMeasurementMessage.class);

        assertThat(m.schemaVersion()).isEqualTo("1.0");
        assertThat(m.measurementId()).isEqualTo("st1-20260722-130501-0007");
        assertThat(m.stationId()).isEqualTo("station-1");
        assertThat(m.status()).isEqualTo("ok");

        assertThat(m.detection().boxCount()).isEqualTo(1);
        assertThat(m.detection().boxes()).hasSize(1);
        assertThat(m.detection().boxes().get(0).bboxPx()).containsExactly(412, 180, 350, 310);
        assertThat(m.detection().boxes().get(0).score()).isEqualTo(0.97);
        assertThat(m.detection().pallet().bboxPx()).containsExactly(380, 460, 520, 140);
        assertThat(m.detection().pallet().score()).isEqualTo(0.99);

        assertThat(m.distance().frontCm()).isEqualTo(152.3);
        assertThat(m.distance().stdCm()).isEqualTo(0.42);
        assertThat(m.distance().framesUsed()).isEqualTo(48);

        assertThat(m.dimensions().heightCm()).isEqualTo(30.2);
        assertThat(m.dimensions().widthCm()).isEqualTo(34.1);
        assertThat(m.dimensions().depthCm()).isNull();
        assertThat(m.dimensions().miniatureScale()).isEqualTo(10);
        assertThat(m.dimensions().miniatureHeightMm()).isEqualTo(30.2);
        assertThat(m.dimensions().miniatureWidthMm()).isEqualTo(34.1);

        assertThat(m.loadBalance().eccentric()).isTrue();
        assertThat(m.loadBalance().direction()).containsExactly("right");
        assertThat(m.loadBalance().ratioX()).isEqualTo(0.40);
        assertThat(m.loadBalance().ratioY()).isEqualTo(0.02);
        assertThat(m.loadBalance().magnitude()).isEqualTo(0.40);
        assertThat(m.loadBalance().threshold()).isEqualTo(0.3);
        assertThat(m.loadBalance().message()).isEqualTo("오른쪽 편하중");
    }

    @Test
    void deserialize_measuredAt_preservesPlus09Offset() throws Exception {
        StationMeasurementMessage m = objectMapper.readValue(FULL_JSON, StationMeasurementMessage.class);

        OffsetDateTime measuredAt = m.measuredAt();
        assertThat(measuredAt.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(measuredAt.toString()).isEqualTo("2026-07-22T13:05:01+09:00");
        // 동일 순간을 UTC로 보면 04:05:01Z
        assertThat(measuredAt.withOffsetSameInstant(ZoneOffset.UTC).toString()).isEqualTo("2026-07-22T04:05:01Z");
    }
}
