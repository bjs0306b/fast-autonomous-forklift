package com.fast.backend.ai.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * cargo/detected MQTT 페이로드(prompt26.md 3장·4장)가 {@link AiCargoAnalysisMessage}로 정확히
 * 역직렬화되는지 검증한다(prompt26.md 16.1장, prompt27.md 10장 시간 필드 점검 보강).
 */
class AiCargoAnalysisMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void deserialize_okStatusFullPayload_mapsAllFields() throws Exception {
        String json = "{"
                + "\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-001\","
                + "\"vehicleId\":\"FORKLIFT-01\",\"cargoId\":\"CARGO-001\",\"status\":\"ok\","
                + "\"detection\":{\"boxes\":["
                + "  {\"className\":\"box\",\"confidence\":0.96,\"bboxPx\":[120,80,340,260]},"
                + "  {\"className\":\"pallet\",\"confidence\":0.93,\"bboxPx\":[90,240,410,160]}"
                + "]},"
                + "\"distance\":{\"valueCm\":185.4,\"stdCm\":2.8},"
                + "\"dimensions\":{\"widthCm\":120.0,\"heightCm\":85.0,\"depthCm\":null,\"volumeCm3\":null,\"scale\":\"REAL\"},"
                + "\"loadBalance\":{\"direction\":[\"left\",\"front\"],\"message\":\"무게 중심이 좌측 전방으로 치우쳐 있습니다.\"},"
                + "\"ratios\":{\"horizontal\":-0.31,\"vertical\":0.18},"
                + "\"message\":\"화물 분석이 완료되었습니다.\","
                + "\"capturedAt\":\"2026-07-22T13:30:00\",\"processedAt\":\"2026-07-22T13:30:01\""
                + "}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.schemaVersion()).isEqualTo("1.0");
        assertThat(message.analysisId()).isEqualTo("ANALYSIS-001");
        assertThat(message.vehicleId()).isEqualTo("FORKLIFT-01");
        assertThat(message.cargoId()).isEqualTo("CARGO-001");
        assertThat(message.status()).isEqualTo("ok");
        assertThat(message.detection().boxes()).hasSize(2);
        assertThat(message.detection().boxes().get(0).className()).isEqualTo("box");
        assertThat(message.detection().boxes().get(0).bboxPx()).containsExactly(120, 80, 340, 260);
        assertThat(message.distance().valueCm()).isEqualTo(185.4);
        assertThat(message.dimensions().widthCm()).isEqualTo(120.0);
        assertThat(message.dimensions().depthCm()).isNull();
        assertThat(message.dimensions().scale()).isEqualTo("REAL");
        assertThat(message.loadBalance().direction()).containsExactly("left", "front");
        assertThat(message.ratios().horizontal()).isEqualTo(-0.31);
        assertThat(message.ratios().vertical()).isEqualTo(0.18);
        assertThat(message.capturedAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 13, 30, 0));
        assertThat(message.processedAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 13, 30, 1));
    }

    @Test
    void deserialize_noDetectionStatus_nullOptionalObjects() throws Exception {
        String json = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-002\",\"vehicleId\":\"FORKLIFT-01\","
                + "\"cargoId\":\"CARGO-002\",\"status\":\"no_detection\",\"detection\":{\"boxes\":[]},"
                + "\"distance\":null,\"dimensions\":null,\"loadBalance\":null,\"ratios\":null,"
                + "\"message\":\"박스 또는 파렛트를 찾지 못했습니다.\","
                + "\"capturedAt\":\"2026-07-22T13:31:00\",\"processedAt\":\"2026-07-22T13:31:01\"}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.status()).isEqualTo("no_detection");
        assertThat(message.detection().boxes()).isEmpty();
        assertThat(message.distance()).isNull();
        assertThat(message.dimensions()).isNull();
        assertThat(message.loadBalance()).isNull();
        assertThat(message.ratios()).isNull();
    }

    @Test
    void deserialize_unreliableStatus_distancePresentDimensionsNull() throws Exception {
        String json = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-003\",\"status\":\"unreliable\","
                + "\"detection\":{\"boxes\":[{\"className\":\"box\",\"confidence\":0.88,\"bboxPx\":[120,80,340,260]}]},"
                + "\"distance\":{\"valueCm\":190.2,\"stdCm\":38.4},\"dimensions\":null,"
                + "\"loadBalance\":null,\"ratios\":null,"
                + "\"message\":\"거리 측정 안정성이 낮아 결과를 사용할 수 없습니다.\","
                + "\"capturedAt\":\"2026-07-22T13:32:00\",\"processedAt\":\"2026-07-22T13:32:01\"}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.status()).isEqualTo("unreliable");
        assertThat(message.distance().stdCm()).isEqualTo(38.4);
        assertThat(message.dimensions()).isNull();
    }

    @Test
    void deserialize_multipleBoxes_preservesOrder() throws Exception {
        String json = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-004\",\"status\":\"ok\","
                + "\"detection\":{\"boxes\":["
                + "{\"className\":\"box\",\"bboxPx\":[1,2,3,4]},"
                + "{\"className\":\"pallet\",\"bboxPx\":[5,6,7,8]}"
                + "]},\"processedAt\":\"2026-07-22T13:30:01\"}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.detection().boxes()).extracting(AiCargoAnalysisMessage.DetectedBox::className)
                .containsExactly("box", "pallet");
    }

    @Test
    void deserialize_millisecondCapturedAtAndProcessedAt_parsesToLocalDateTime() throws Exception {
        // prompt27.md 10장: capturedAt/processedAt도 밀리초 단위 ISO-8601을 파싱해야 한다.
        String json = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-TIME-01\",\"status\":\"ok\","
                + "\"capturedAt\":\"2026-07-22T13:30:00.123\",\"processedAt\":\"2026-07-22T13:30:01.456\"}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.capturedAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 13, 30, 0, 123_000_000));
        assertThat(message.processedAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 13, 30, 1, 456_000_000));
    }

    @Test
    void deserialize_invalidProcessedAtFormat_throwsJsonProcessingException() {
        // prompt27.md 10장·11장: 잘못된 시간 문자열은 해당 메시지만 폐기해야 하므로 역직렬화 자체가
        // 실패해야 한다(LocalDateTime.now()로 자동 대체하지 않음).
        String json = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-TIME-02\",\"status\":\"ok\","
                + "\"processedAt\":\"not-a-valid-timestamp\"}";

        assertThatThrownBy(() -> objectMapper.readValue(json, AiCargoAnalysisMessage.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void deserialize_unsupportedSchemaVersion_stillParsesAsString() throws Exception {
        // schemaVersion 검증 자체는 Service 책임이다 — DTO 역직렬화 단계에서는 문자열이면 무엇이든 받는다.
        String json = "{\"schemaVersion\":\"2.0\",\"analysisId\":\"ANALYSIS-005\",\"status\":\"ok\","
                + "\"processedAt\":\"2026-07-22T13:30:01\"}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.schemaVersion()).isEqualTo("2.0");
    }

    @Test
    void deserialize_missingOptionalFields_leavesThemNull() throws Exception {
        String json = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-006\",\"status\":\"no_detection\","
                + "\"processedAt\":\"2026-07-22T13:30:01\"}";

        AiCargoAnalysisMessage message = objectMapper.readValue(json, AiCargoAnalysisMessage.class);

        assertThat(message.vehicleId()).isNull();
        assertThat(message.cargoId()).isNull();
        assertThat(message.detection()).isNull();
        assertThat(message.distance()).isNull();
        assertThat(message.dimensions()).isNull();
        assertThat(message.loadBalance()).isNull();
        assertThat(message.ratios()).isNull();
        assertThat(message.capturedAt()).isNull();
    }
}
