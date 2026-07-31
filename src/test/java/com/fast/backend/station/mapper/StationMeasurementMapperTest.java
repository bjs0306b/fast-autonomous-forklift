package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
<<<<<<< HEAD
=======
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import org.junit.jupiter.api.BeforeEach;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
<<<<<<< HEAD
=======
import org.springframework.dao.DuplicateKeyException;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
<<<<<<< HEAD
import java.util.Optional;
=======
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
<<<<<<< HEAD
 * station_measurement insert/조회, measurement_id UNIQUE 제약, measured_at UTC+offset 저장이 실제
 * H2(MySQL 호환 모드)에서 동작하는지 검증한다(prompt16.md 11단계).
=======
 * {@code station_measurement} 왕복 검증(prompt95.md 24장) — 실제 Mapper XML과 H2(MySQL 호환 모드)의
 * CHECK/UNIQUE 제약을 함께 태운다.
 *
 * <p>REST 전환에서 Mapper XML은 <b>바꾸지 않았다</b>({@code tipping_level}/{@code overhang_ratio}
 * INSERT가 이미 있었다). 이 테스트는 그 컬럼들이 실제로 왕복하는지, 그리고 DB 제약이 잘못된 값을
 * 막아 주는지를 고정한다 — 옛 Adapter는 두 값을 항상 null로 넣어서 왕복이 검증된 적이 없었다.
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementMapperTest {

<<<<<<< HEAD
    @Autowired
    private StationMeasurementMapper mapper;

    @Test
    void insert_generatesId() {
        StationMeasurement m = newMeasurement("SM-01", "station-1");
        mapper.insert(m);
        assertThat(m.getId()).isNotNull();
    }

    @Test
    void findByMeasurementId_returnsInsertedRow_withOffsetPreservedSeparately() {
        StationMeasurement m = newMeasurement("SM-02", "station-1");
        m.setMeasuredAtOffsetMinutes(540);
        mapper.insert(m);

        Optional<StationMeasurement> found = mapper.findByMeasurementId("SM-02");
        assertThat(found).isPresent();
        assertThat(found.get().getStationId()).isEqualTo("station-1");
        assertThat(found.get().getMeasuredAtOffsetMinutes()).isEqualTo(540);
        assertThat(found.get().getStatus()).isEqualTo(StationMeasurementStatus.OK);
    }

    @Test
    void existsByMeasurementId_reflectsInsert() {
        assertThat(mapper.existsByMeasurementId("SM-03")).isFalse();
        mapper.insert(newMeasurement("SM-03", "station-1"));
        assertThat(mapper.existsByMeasurementId("SM-03")).isTrue();
    }

    @Test
    void insert_duplicateMeasurementId_violatesUniqueConstraint() {
        mapper.insert(newMeasurement("SM-04", "station-1"));
        assertThatThrownBy(() -> mapper.insert(newMeasurement("SM-04", "station-2")))
=======
    @Autowired private StationMeasurementMapper measurementMapper;
    @Autowired private StationSessionMapper sessionMapper;
    @Autowired private CargoMapper cargoMapper;

    private String sessionId;

    @BeforeEach
    void setUp() {
        Cargo cargo = new Cargo();
        cargo.setCargoId("SMM-CARGO-1");
        cargo.setCreatedAt(LocalDateTime.now());
        cargoMapper.insert(cargo);

        sessionId = "SMM-SESSION-1";
        sessionMapper.insert(new StationSession(sessionId, "SMM-CARGO-1"));
    }

    @Test
    void insert_roundTripsOkRow_withMeterHeightAndUppercaseLevel() {
        measurementMapper.insert(measurement("M-OK", StationMeasurementStatus.OK, 0.723, "SAFE", 0.057));

        StationMeasurement found = measurementMapper.findByMeasurementId("M-OK").orElseThrow();
        assertThat(found.getSequenceNo()).isNotNull();
        assertThat(found.getSessionId()).isEqualTo(sessionId);
        assertThat(found.getStatus()).isEqualTo(StationMeasurementStatus.OK);
        assertThat(found.getCargoHeight()).isEqualTo(0.723);
        assertThat(found.getTippingLevel()).isEqualTo("SAFE");
        assertThat(found.getOverhangRatio()).isEqualTo(0.057);
    }

    @Test
    void insert_acceptsAllFourStatuses() {
        measurementMapper.insert(measurement("M-S1", StationMeasurementStatus.OK, 0.7, "WARNING", 0.02));
        measurementMapper.insert(measurement("M-S2", StationMeasurementStatus.DIMENSIONS_ONLY, 0.7, null, null));
        measurementMapper.insert(measurement("M-S3", StationMeasurementStatus.NO_DETECTION, null, null, null));
        measurementMapper.insert(measurement("M-S4", StationMeasurementStatus.UNRELIABLE, null, null, null));

        // DIMENSIONS_ONLY 는 enum 에 뒤늦게 추가됐다(DB CHECK 에는 원래 있었다) — 실제로 통과하는지 본다.
        assertThat(measurementMapper.findByMeasurementId("M-S2").orElseThrow().getStatus())
                .isEqualTo(StationMeasurementStatus.DIMENSIONS_ONLY);
        assertThat(measurementMapper.findByMeasurementId("M-S3").orElseThrow().getCargoHeight()).isNull();
        assertThat(measurementMapper.findByMeasurementId("M-S4").orElseThrow().getTippingLevel()).isNull();
    }

    @Test
    void findLatestBySessionId_returnsMostRecentlyReceivedRow() {
        measurementMapper.insert(measurement("M-L1", StationMeasurementStatus.OK, 0.5, "SAFE", 0.0));
        measurementMapper.insert(measurement("M-L2", StationMeasurementStatus.OK, 0.9, "DANGER", 0.4));

        // 정렬 기준은 sequence_no(수신 순서)다 — 측정 시각 컬럼이 없다.
        assertThat(measurementMapper.findLatestBySessionId(sessionId).orElseThrow().getMeasurementId())
                .isEqualTo("M-L2");
    }

    @Test
    void existsByMeasurementId_andUniqueConstraint() {
        measurementMapper.insert(measurement("M-U1", StationMeasurementStatus.OK, 0.5, "SAFE", 0.0));

        assertThat(measurementMapper.existsByMeasurementId("M-U1")).isTrue();
        assertThat(measurementMapper.existsByMeasurementId("M-U-NONE")).isFalse();

        assertThatThrownBy(() ->
                measurementMapper.insert(measurement("M-U1", StationMeasurementStatus.OK, 0.6, "SAFE", 0.0)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void checkConstraint_rejectsLowercaseTippingLevel() {
        // 이 제약이 있기 때문에 Service 의 대문자 정규화가 필수다 — 소문자를 그대로 넣으면 여기서 죽는다.
        assertThatThrownBy(() ->
                measurementMapper.insert(measurement("M-C1", StationMeasurementStatus.OK, 0.5, "safe", 0.0)))
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
<<<<<<< HEAD
    void findLatestByStationId_returnsMostRecentByMeasuredAt() {
        StationMeasurement older = newMeasurement("SM-05a", "station-9");
        older.setMeasuredAtUtc(LocalDateTime.now().minusMinutes(10));
        StationMeasurement newer = newMeasurement("SM-05b", "station-9");
        newer.setMeasuredAtUtc(LocalDateTime.now());
        mapper.insert(older);
        mapper.insert(newer);

        Optional<StationMeasurement> latest = mapper.findLatestByStationId("station-9");
        assertThat(latest).isPresent();
        assertThat(latest.get().getMeasurementId()).isEqualTo("SM-05b");
    }

    @Test
    void findLatestByStationId_noRows_returnsEmpty() {
        assertThat(mapper.findLatestByStationId("no-such-station")).isEmpty();
    }

    private StationMeasurement newMeasurement(String measurementId, String stationId) {
        LocalDateTime now = LocalDateTime.now();
        StationMeasurement m = new StationMeasurement();
        m.setMeasurementId(measurementId);
        m.setStationId(stationId);
        m.setSchemaVersion("1.0");
        m.setMeasuredAtUtc(now);
        m.setMeasuredAtOffsetMinutes(540);
        m.setStatus(StationMeasurementStatus.OK);
        m.setBoxCount(1);
        m.setFrontCm(152.3);
        m.setDistanceStdCm(0.42);
        m.setFramesUsed(48);
        m.setHeightCm(30.2);
        m.setWidthCm(34.1);
        m.setDepthCm(null);
        m.setMiniatureScale(10);
        m.setMiniatureHeightMm(30.2);
        m.setMiniatureWidthMm(34.1);
        m.setEccentric(true);
        m.setLoadDirection("RIGHT");
        m.setRatioX(0.40);
        m.setRatioY(0.02);
        m.setMagnitude(0.40);
        m.setThreshold(0.3);
        m.setLoadMessage("오른쪽 편하중");
        m.setReceivedAt(now);
        m.setCreatedAt(now);
=======
    void checkConstraint_rejectsNonPositiveHeightAndNegativeOverhang() {
        assertThatThrownBy(() ->
                measurementMapper.insert(measurement("M-C2", StationMeasurementStatus.OK, 0.0, "SAFE", 0.0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                measurementMapper.insert(measurement("M-C3", StationMeasurementStatus.OK, 0.5, "SAFE", -0.1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private StationMeasurement measurement(String measurementId, StationMeasurementStatus status,
            Double cargoHeight, String tippingLevel, Double overhangRatio) {
        StationMeasurement m = new StationMeasurement();
        m.setMeasurementId(measurementId);
        m.setSessionId(sessionId);
        m.setStatus(status);
        m.setCargoHeight(cargoHeight);
        m.setTippingLevel(tippingLevel);
        m.setOverhangRatio(overhangRatio);
        m.setCreatedAt(LocalDateTime.now());
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
        return m;
    }
}
