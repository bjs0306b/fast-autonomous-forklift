package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * station_measurement insert/조회, measurement_id UNIQUE 제약, measured_at UTC+offset 저장이 실제
 * H2(MySQL 호환 모드)에서 동작하는지 검증한다(prompt16.md 11단계).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementMapperTest {

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
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
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
        return m;
    }
}
