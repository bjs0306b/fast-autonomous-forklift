package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.dto.VehicleCreateRequest;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusCountRow;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 차량 INSERT 뒤 초기 current status 저장이 실패하면 차량 INSERT도 롤백되는지 실제 H2 트랜잭션으로 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class VehicleRegistrationRollbackIntegrationTest {

    private static final String VEHICLE_ID = "REGISTER-ROLLBACK-01";

    @Autowired
    private VehicleService vehicleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM vehicle_current_status WHERE vehicle_id = ?", VEHICLE_ID);
        jdbcTemplate.update("DELETE FROM vehicle WHERE vehicle_id = ?", VEHICLE_ID);
    }

    @Test
    void register_initialStatusSaveFails_rollsBackVehicleInsert() {
        VehicleCreateRequest request = new VehicleCreateRequest(VEHICLE_ID, "롤백 검증 차량", VehicleSource.REAL);

        assertThatThrownBy(() -> vehicleService.register(request))
                .isInstanceOf(DataAccessException.class);

        Integer vehicleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle WHERE vehicle_id = ?", Integer.class, VEHICLE_ID);
        Integer statusCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle_current_status WHERE vehicle_id = ?", Integer.class, VEHICLE_ID);
        assertThat(vehicleCount).isZero();
        assertThat(statusCount).isZero();
    }

    @TestConfiguration
    static class ThrowingCurrentStatusMapperConfig {

        @Bean
        @Primary
        VehicleCurrentStatusMapper throwingVehicleCurrentStatusMapper() {
            return new VehicleCurrentStatusMapper() {
                @Override
                public Optional<VehicleCurrentStatus> findByVehicleId(String vehicleId) {
                    return Optional.empty();
                }

                @Override
                public List<VehicleCurrentStatus> findAllByVehicleIds(List<String> vehicleIds) {
                    return List.of();
                }

                @Override
                public void upsert(VehicleCurrentStatus status) {
                    throw new DataIntegrityViolationException(
                            "forced vehicle_current_status initial save failure (test only)");
                }

                @Override
                public List<VehicleStatusCountRow> countByStatusForActiveVehicles() {
                    return List.of();
                }
            };
        }
    }
}
