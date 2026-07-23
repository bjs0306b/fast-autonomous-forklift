package com.fast.backend.station.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.mqtt.inbound.MqttMessageRouter;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementMessage.Detection;
import com.fast.backend.station.dto.StationMeasurementMessage.DetectedBox;
import com.fast.backend.station.dto.StationMeasurementMessage.Dimensions;
import com.fast.backend.station.dto.StationMeasurementMessage.Distance;
import com.fast.backend.station.dto.StationMeasurementMessage.LoadBalance;
import com.fast.backend.station.dto.StationMeasurementMessage.PalletDetection;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.mapper.StationMeasurementBoxMapper;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 측정 스테이션 v1.0 수신 파이프라인(MQTT Router → 검증 → 저장 → 조회)을 실제 Spring 컨텍스트 + H2로
 * 관통 검증한다(prompt16.md 11단계). Mockito를 전혀 쓰지 않으므로 JDK/Mockito와 무관하게 실행된다.
 *
 * <p>수신은 실제 {@link MqttMessageRouter#route}를 통해 흘려보낸다 — 메시지를 프로젝트 ObjectMapper로
 * 직렬화(@JsonProperty snake_case)해 route()에 넣으면, 토픽 분기·station_id 일치 검증·역직렬화·서비스
 * 검증·저장까지 실제 경로가 한 번에 검증된다. 검증 실패/중복 시 서비스가 BusinessException을 흡수하므로
 * (메시지 폐기), "저장되지 않음"을 관찰해 정책을 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementIntegrationTest {

    @Autowired
    private MqttMessageRouter router;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StationMeasurementService service;

    @Autowired
    private StationMeasurementMapper measurementMapper;

    @Autowired
    private StationMeasurementBoxMapper boxMapper;

    private static final OffsetDateTime MEASURED_AT = OffsetDateTime.of(2026, 7, 22, 13, 5, 1, 0, ZoneOffset.ofHours(9));

    // ── 정상 저장 (테스트 4) + station_id 일치 (테스트 3) ─────────────────────

    @Test
    void ok_storesMeasurementBoxesPalletDistanceDimensionsLoadBalance() {
        route("station-1", okMessage("SM-OK-01", "station-1"));

        StationMeasurement m = measurementMapper.findByMeasurementId("SM-OK-01").orElseThrow();
        assertThat(m.getStatus()).isEqualTo(StationMeasurementStatus.OK);
        assertThat(m.getStationId()).isEqualTo("station-1");
        assertThat(m.getBoxCount()).isEqualTo(1);
        assertThat(m.getPalletScore()).isEqualTo(0.99);
        assertThat(m.getPalletBboxX()).isEqualTo(380);
        assertThat(m.getFrontCm()).isEqualTo(152.3);
        assertThat(m.getFramesUsed()).isEqualTo(48);
        assertThat(m.getHeightCm()).isEqualTo(30.2);
        assertThat(m.getMiniatureScale()).isEqualTo(10);
        assertThat(m.getEccentric()).isTrue();
        assertThat(m.getLoadDirection()).isEqualTo("RIGHT");
        assertThat(m.getDepthCm()).isNull();

        assertThat(boxMapper.findByStationMeasurementId(m.getId())).hasSize(1);

        // 조회 응답에서 measured_at 오프셋(+09:00)이 손실 없이 복원되는지
        StationMeasurementResponse resp = service.findByMeasurementId("SM-OK-01");
        assertThat(resp.measuredAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(resp.measuredAt().toString()).isEqualTo("2026-07-22T13:05:01+09:00");
        assertThat(resp.detection().pallet().bboxPx()).containsExactly(380, 460, 520, 140);
    }

    @Test
    void stationIdMismatchBetweenTopicAndPayload_discardsMessage() {
        // 토픽 station_id=station-1, payload station_id=station-2 → 폐기
        route("station-1", okMessage("SM-MISMATCH-01", "station-2"));
        assertThat(measurementMapper.existsByMeasurementId("SM-MISMATCH-01")).isFalse();
    }

    // ── status 정책 (테스트 2, 5, 6) ──────────────────────────────────────────

    @Test
    void noDetection_withNullDimensionsAndLoadBalance_isStored() {
        StationMeasurementMessage msg = new StationMeasurementMessage("1.0", "SM-ND-01", "station-1", MEASURED_AT,
                "no_detection", new Detection(0, List.of(), null), null, null, null);
        route("station-1", msg);

        StationMeasurement m = measurementMapper.findByMeasurementId("SM-ND-01").orElseThrow();
        assertThat(m.getStatus()).isEqualTo(StationMeasurementStatus.NO_DETECTION);
        assertThat(m.getHeightCm()).isNull();
        assertThat(m.getLoadDirection()).isNull();
        assertThat(m.getEccentric()).isNull();
    }

    @Test
    void unreliable_withNullDimensionsAndLoadBalance_isStored() {
        StationMeasurementMessage msg = new StationMeasurementMessage("1.0", "SM-UR-01", "station-1", MEASURED_AT,
                "unreliable", null, null, null, null);
        route("station-1", msg);

        StationMeasurement m = measurementMapper.findByMeasurementId("SM-UR-01").orElseThrow();
        assertThat(m.getStatus()).isEqualTo(StationMeasurementStatus.UNRELIABLE);
        assertThat(m.getWidthCm()).isNull();
    }

    @Test
    void unsupportedStatus_isRejected() {
        StationMeasurementMessage msg = new StationMeasurementMessage("1.0", "SM-BAD-STATUS", "station-1", MEASURED_AT,
                "weird", null, null, null, null);
        route("station-1", msg);
        assertThat(measurementMapper.existsByMeasurementId("SM-BAD-STATUS")).isFalse();
    }

    @Test
    void notOk_butDimensionsPresent_isRejected() {
        StationMeasurementMessage msg = new StationMeasurementMessage("1.0", "SM-ND-BAD", "station-1", MEASURED_AT,
                "no_detection", null, null, dimensions(30.2, 34.1, null, 10, 30.2, 34.1), null);
        route("station-1", msg);
        assertThat(measurementMapper.existsByMeasurementId("SM-ND-BAD")).isFalse();
    }

    // ── dimensions 정책 (테스트 7, 8, 9) ──────────────────────────────────────

    @Test
    void depthCmPresent_isRejected() {
        StationMeasurementMessage msg = withDimensions("SM-DEPTH", dimensions(30.2, 34.1, 5.0, 10, 30.2, 34.1));
        route("station-1", msg);
        assertThat(measurementMapper.existsByMeasurementId("SM-DEPTH")).isFalse();
    }

    @Test
    void miniatureScaleNot10_isRejected() {
        StationMeasurementMessage msg = withDimensions("SM-SCALE", dimensions(30.2, 34.1, null, 5, 30.2, 34.1));
        route("station-1", msg);
        assertThat(measurementMapper.existsByMeasurementId("SM-SCALE")).isFalse();
    }

    @Test
    void miniatureMmMismatch_isRejected() {
        // height_cm=30.2 인데 miniature_height_mm=99.9 → 불일치
        StationMeasurementMessage msg = withDimensions("SM-MM", dimensions(30.2, 34.1, null, 10, 99.9, 34.1));
        route("station-1", msg);
        assertThat(measurementMapper.existsByMeasurementId("SM-MM")).isFalse();
    }

    // ── detection 정책 (테스트 10, 11) ───────────────────────────────────────

    @Test
    void bboxPxNull_isAllowed() {
        Detection detection = new Detection(1, List.of(new DetectedBox(null, 0.9)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        StationMeasurementMessage msg = new StationMeasurementMessage("1.0", "SM-BBOXNULL", "station-1", MEASURED_AT,
                "ok", detection, distance(), dimensions(30.2, 34.1, null, 10, 30.2, 34.1), loadBalance());
        route("station-1", msg);

        StationMeasurement m = measurementMapper.findByMeasurementId("SM-BBOXNULL").orElseThrow();
        assertThat(boxMapper.findByStationMeasurementId(m.getId()).get(0).getBboxX()).isNull();
    }

    @Test
    void bboxPxNotFourElements_isRejected() {
        Detection detection = new Detection(1, List.of(new DetectedBox(List.of(1, 2, 3), 0.9)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        StationMeasurementMessage msg = new StationMeasurementMessage("1.0", "SM-BBOX3", "station-1", MEASURED_AT,
                "ok", detection, distance(), dimensions(30.2, 34.1, null, 10, 30.2, 34.1), loadBalance());
        route("station-1", msg);
        assertThat(measurementMapper.existsByMeasurementId("SM-BBOX3")).isFalse();
    }

    // ── load_balance 정책 (테스트 12, 13, 14, 15) ────────────────────────────

    @Test
    void twoDiagonalDirections_areAllowed() {
        LoadBalance lb = new LoadBalance(true, List.of("right", "front"), 0.40, 0.02, 0.40, 0.3, "대각");
        route("station-1", withLoadBalance("SM-DIAG", lb));
        StationMeasurement m = measurementMapper.findByMeasurementId("SM-DIAG").orElseThrow();
        assertThat(m.getLoadDirection()).isEqualTo("RIGHT,FRONT");
    }

    @Test
    void threeDirections_isRejected() {
        LoadBalance lb = new LoadBalance(true, List.of("right", "front", "left"), 0.40, 0.02, 0.40, 0.3, "x");
        route("station-1", withLoadBalance("SM-DIR3", lb));
        assertThat(measurementMapper.existsByMeasurementId("SM-DIR3")).isFalse();
    }

    @Test
    void eccentricMismatch_isRejected() {
        // magnitude=max(0.40,0.02)=0.40 > threshold 0.3 → eccentric은 true여야 하는데 false로 옴
        LoadBalance lb = new LoadBalance(false, List.of("right"), 0.40, 0.02, 0.40, 0.3, "x");
        route("station-1", withLoadBalance("SM-ECC", lb));
        assertThat(measurementMapper.existsByMeasurementId("SM-ECC")).isFalse();
    }

    @Test
    void magnitudeMismatch_isRejected() {
        // magnitude는 max(0.40,0.02)=0.40 이어야 하는데 0.99로 옴
        LoadBalance lb = new LoadBalance(true, List.of("right"), 0.40, 0.02, 0.99, 0.3, "x");
        route("station-1", withLoadBalance("SM-MAG", lb));
        assertThat(measurementMapper.existsByMeasurementId("SM-MAG")).isFalse();
    }

    // ── 중복 measurement_id (테스트 16) ──────────────────────────────────────

    @Test
    void duplicateMeasurementId_secondIsIgnored_originalKept() {
        route("station-1", okMessage("SM-DUP", "station-1"));
        // 같은 measurement_id, 다른 station_id로 재수신 → 무시, 원본 유지
        route("station-9", okMessage("SM-DUP", "station-9"));

        StationMeasurement m = measurementMapper.findByMeasurementId("SM-DUP").orElseThrow();
        assertThat(m.getStationId()).isEqualTo("station-1"); // 원본 보존
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void route(String topicStationId, StationMeasurementMessage message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            router.route("fast/station/" + topicStationId + "/measurement", payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StationMeasurementMessage okMessage(String measurementId, String stationId) {
        Detection detection = new Detection(1,
                List.of(new DetectedBox(List.of(412, 180, 350, 310), 0.97)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        return new StationMeasurementMessage("1.0", measurementId, stationId, MEASURED_AT, "ok",
                detection, distance(), dimensions(30.2, 34.1, null, 10, 30.2, 34.1), loadBalance());
    }

    private StationMeasurementMessage withDimensions(String measurementId, Dimensions dimensions) {
        Detection detection = new Detection(1,
                List.of(new DetectedBox(List.of(412, 180, 350, 310), 0.97)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        return new StationMeasurementMessage("1.0", measurementId, "station-1", MEASURED_AT, "ok",
                detection, distance(), dimensions, loadBalance());
    }

    private StationMeasurementMessage withLoadBalance(String measurementId, LoadBalance loadBalance) {
        Detection detection = new Detection(1,
                List.of(new DetectedBox(List.of(412, 180, 350, 310), 0.97)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        return new StationMeasurementMessage("1.0", measurementId, "station-1", MEASURED_AT, "ok",
                detection, distance(), dimensions(30.2, 34.1, null, 10, 30.2, 34.1), loadBalance);
    }

    private Distance distance() {
        return new Distance(152.3, 0.42, 48);
    }

    private Dimensions dimensions(Double h, Double w, Double depth, Integer scale, Double mh, Double mw) {
        return new Dimensions(h, w, depth, scale, mh, mw);
    }

    private LoadBalance loadBalance() {
        return new LoadBalance(true, List.of("right"), 0.40, 0.02, 0.40, 0.3, "오른쪽 편하중");
    }
}
