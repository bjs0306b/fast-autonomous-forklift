package com.fast.backend.station.service;

import com.fast.backend.station.domain.StationMeasurementBox;
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementMessage.Detection;
import com.fast.backend.station.dto.StationMeasurementMessage.DetectedBox;
import com.fast.backend.station.dto.StationMeasurementMessage.Dimensions;
import com.fast.backend.station.dto.StationMeasurementMessage.Distance;
import com.fast.backend.station.dto.StationMeasurementMessage.LoadBalance;
import com.fast.backend.station.dto.StationMeasurementMessage.PalletDetection;
import com.fast.backend.station.mapper.StationMeasurementBoxMapper;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테스트 17(prompt16.md 11단계): 부모 station_measurement insert 성공 후 자식 box insert에서 예외가 나면,
 * 같은 {@code @Transactional}의 부모 insert도 함께 롤백되는지 실제 H2 트랜잭션으로 검증한다.
 *
 * <p>롤백을 관찰하려면 서비스의 트랜잭션이 실제 커밋 경계여야 하므로 이 클래스는 {@code @Transactional}을
 * 걸지 않는다(걸면 부모 insert가 같은 물리 트랜잭션 안에서 계속 보여 롤백을 구분 불가). box insert 실패는
 * 손수 작성한 {@code @Primary} Mapper 구현이 실제 {@link DataAccessException}을 던져 유발한다 — Mockito/
 * SpyBean 미사용(JDK25 호환), 운영 코드에 실패 플래그를 추가하지 않는다(원칙 6번, prompt16.md 11단계).
 */
@SpringBootTest
@ActiveProfiles("test")
class StationMeasurementRollbackIntegrationTest {

    private static final String MEASUREMENT_ID = "SM-ROLLBACK-01";
    private static final OffsetDateTime MEASURED_AT =
            OffsetDateTime.of(2026, 7, 22, 13, 5, 1, 0, ZoneOffset.ofHours(9));

    @Autowired
    private StationMeasurementService service;

    @Autowired
    private StationMeasurementMapper measurementMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM station_measurement_box WHERE station_measurement_id IN "
                + "(SELECT id FROM station_measurement WHERE measurement_id = ?)", MEASUREMENT_ID);
        jdbcTemplate.update("DELETE FROM station_measurement WHERE measurement_id = ?", MEASUREMENT_ID);
    }

    @Test
    void boxInsertFails_rollsBackParentMeasurement() {
        assertThatThrownBy(() -> service.process(okMessage()))
                .isInstanceOf(DataAccessException.class);

        // 부모도 롤백되어 저장되지 않는다
        assertThat(measurementMapper.existsByMeasurementId(MEASUREMENT_ID)).isFalse();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM station_measurement WHERE measurement_id = ?", Integer.class, MEASUREMENT_ID);
        assertThat(count).isZero();
    }

    private StationMeasurementMessage okMessage() {
        Detection detection = new Detection(1,
                List.of(new DetectedBox(List.of(412, 180, 350, 310), 0.97)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        return new StationMeasurementMessage("1.0", MEASUREMENT_ID, "station-1", MEASURED_AT, "ok",
                detection,
                new Distance(152.3, 0.42, 48),
                new Dimensions(30.2, 34.1, null, 10, 30.2, 34.1),
                new LoadBalance(true, List.of("right"), 0.40, 0.02, 0.40, 0.3, "오른쪽 편하중"));
    }

    @TestConfiguration
    static class ThrowingBoxMapperConfig {

        @Bean
        @Primary
        StationMeasurementBoxMapper throwingStationMeasurementBoxMapper() {
            return new StationMeasurementBoxMapper() {
                @Override
                public int insert(StationMeasurementBox box) {
                    throw new DataIntegrityViolationException(
                            "forced station_measurement_box insert failure (test only)");
                }

                @Override
                public List<StationMeasurementBox> findByStationMeasurementId(Long stationMeasurementId) {
                    return List.of();
                }
            };
        }
    }
}
