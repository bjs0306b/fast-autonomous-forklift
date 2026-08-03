package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.CargoMeasuredHeight;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관제 대시보드가 쓰는 화물 높이 일괄 조회 검증.
 *
 * <p>이 쿼리는 화물 식별자를 IN 으로 묶어 <b>차량 수와 무관하게 1회만</b> 실행된다. 검증 포인트는
 * 세 가지다 — 세션을 건너 화물로 조인되는지, 재측정 시 최신 1건이 남는지, 높이 없는 측정이
 * 제외되는지.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationMeasurementCargoHeightIntegrationTest {

    @Autowired
    private StationMeasurementMapper measurementMapper;

    @Autowired
    private StationSessionMapper sessionMapper;

    @Autowired
    private CargoMapper cargoMapper;

    @Test
    void multipleMeasurements_returnRowsOrderedSoLatestWins() {
        insertCargo("CARGO-HEIGHT-1");
        // 같은 화물을 두 번 측정했다(세션이 두 개). 최신 측정이 유효한 높이여야 한다.
        insertMeasurement("CARGO-HEIGHT-1", "SESSION-H1-A", "M-H1-A", 0.50);
        insertMeasurement("CARGO-HEIGHT-1", "SESSION-H1-B", "M-H1-B", 1.25);

        Map<String, Double> heights = collect(measurementMapper.findLatestCargoHeights(List.of("CARGO-HEIGHT-1")));

        // 호출부(MonitoringService)와 같은 방식으로 뒤 행이 앞 행을 덮는다.
        assertThat(heights).containsEntry("CARGO-HEIGHT-1", 1.25);
    }

    @Test
    void measurementWithoutHeight_isExcluded() {
        insertCargo("CARGO-HEIGHT-2");
        insertMeasurement("CARGO-HEIGHT-2", "SESSION-H2", "M-H2", null);

        List<CargoMeasuredHeight> rows = measurementMapper.findLatestCargoHeights(List.of("CARGO-HEIGHT-2"));

        assertThat(rows).isEmpty();
    }

    @Test
    void multipleCargos_areResolvedInSingleQuery() {
        insertCargo("CARGO-HEIGHT-3");
        insertCargo("CARGO-HEIGHT-4");
        insertMeasurement("CARGO-HEIGHT-3", "SESSION-H3", "M-H3", 0.72);
        insertMeasurement("CARGO-HEIGHT-4", "SESSION-H4", "M-H4", 1.08);

        Map<String, Double> heights = collect(measurementMapper.findLatestCargoHeights(
                List.of("CARGO-HEIGHT-3", "CARGO-HEIGHT-4", "CARGO-NOT-MEASURED")));

        assertThat(heights).containsEntry("CARGO-HEIGHT-3", 0.72);
        assertThat(heights).containsEntry("CARGO-HEIGHT-4", 1.08);
        // 측정 이력이 없는 화물은 아예 행이 없다 → 화면은 "측정 정보 없음"이 된다.
        assertThat(heights).doesNotContainKey("CARGO-NOT-MEASURED");
    }

    private static Map<String, Double> collect(List<CargoMeasuredHeight> rows) {
        Map<String, Double> heights = new HashMap<>();
        rows.forEach(row -> heights.put(row.cargoId(), row.cargoHeight()));
        return heights;
    }

    private void insertCargo(String cargoId) {
        Cargo cargo = new Cargo();
        cargo.setCargoId(cargoId);
        cargo.setCreatedAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        cargoMapper.insert(cargo);
    }

    private void insertMeasurement(String cargoId, String sessionId, String measurementId, Double cargoHeight) {
        StationSession session = new StationSession();
        session.setSessionId(sessionId);
        session.setCargoId(cargoId);
        sessionMapper.insert(session);

        StationMeasurement measurement = new StationMeasurement();
        measurement.setMeasurementId(measurementId);
        measurement.setSessionId(sessionId);
        measurement.setStatus(cargoHeight == null
                ? StationMeasurementStatus.NO_DETECTION
                : StationMeasurementStatus.OK);
        measurement.setCargoHeight(cargoHeight);
        measurement.setTippingLevel(cargoHeight == null ? null : "SAFE");
        measurement.setOverhangRatio(cargoHeight == null ? null : 0.04);
        measurement.setCreatedAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        measurementMapper.insert(measurement);
    }
}
