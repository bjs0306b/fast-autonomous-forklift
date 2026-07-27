package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 테스트 5(prompt14.md): {@code vehicle_current_status} upsert 성공 후
 * {@code vehicle_status_history} insert에서 예외가 나면, 같은 트랜잭션의 최신 상태 갱신도 함께
 * 롤백되는지 실제 H2 트랜잭션으로 검증한다.
 *
 * <p><b>왜 별도 클래스인가</b>: 롤백을 관찰하려면 서비스의 {@code @Transactional}이 <i>실제 커밋
 * 경계</i>여야 한다. {@link VehicleStatusServiceIntegrationTest}처럼 테스트에 클래스 레벨
 * {@code @Transactional}을 걸면 서비스 트랜잭션이 테스트 트랜잭션에 합류(REQUIRED)해, upsert가
 * 같은 물리 트랜잭션 안에서 여전히 보이므로 "롤백됐다"를 구분할 수 없다. 그래서 이 클래스는
 * {@code @Transactional}을 걸지 않아 서비스가 자체 트랜잭션을 새로 시작하게 하고, 예외 후
 * auto-commit 읽기로 실제 롤백 결과를 확인한다.
 *
 * <p><b>왜 Mockito가 아닌가</b>: prompt14.md의 권장 1순위(테스트 전용 Mapper 구현 / @TestConfiguration로
 * history insert 시 DataAccessException 발생)를 따랐다. {@link ThrowingHistoryMapperConfig}가 이력
 * insert에서만 실제 {@link DataAccessException}(=RuntimeException)을 던지는 손수 작성한 Mapper 구현을
 * {@code @Primary}로 주입한다. {@code current_status} upsert는 진짜 H2 Mapper 그대로라, 롤백 대상이
 * 실제 DB 쓰기임을 보장한다. SpyBean 등 Mockito 계열은 JDK/Mockito 비호환 위험이 있어 쓰지 않는다.
 *
 * <p>운영 {@code schema.sql}과 운영 Service 코드는 전혀 건드리지 않는다(실패 플래그/조건문 추가 없음).
 */
@SpringBootTest
@ActiveProfiles("test")
class VehicleStatusServiceRollbackIntegrationTest {

    private static final String VEHICLE_ID = "ROLLBACK-01";

    @Autowired
    private VehicleStatusService vehicleStatusService;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 이 클래스는 @Transactional이 아니라 테스트 데이터가 실제로 커밋된다. 각 테스트 후 반드시 정리한다
     * (FK 때문에 자식 → 부모 순서로 삭제). DELETE만 사용(운영 데이터가 아니라 이 테스트가 넣은 행만 대상).
     */
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM vehicle_status_history WHERE vehicle_id = ?", VEHICLE_ID);
        jdbcTemplate.update("DELETE FROM vehicle_current_status WHERE vehicle_id = ?", VEHICLE_ID);
        jdbcTemplate.update("DELETE FROM vehicle WHERE vehicle_id = ?", VEHICLE_ID);
    }

    @Test
    void updateCurrentStatus_historyInsertFails_rollsBackCurrentStatusUpsert() {
        // Given: 차량이 커밋된 상태로 존재(서비스 트랜잭션 밖에서 insert → auto-commit)
        insertVehicleCommitted(VEHICLE_ID);
        LocalDateTime t1 = LocalDateTime.now().withNano(0);

        // When: 이력 insert가 예외를 던지도록 구성된 상태에서 상태 갱신 호출
        // Then: 예외가 호출자에게 전파된다
        assertThatThrownBy(() -> vehicleStatusService.updateCurrentStatus(VEHICLE_ID,
                new VehicleStatusUpdateCommand("ACTIVE", 77, 1.0, 2.0, 30.0, 0.4, t1.atOffset(java.time.ZoneOffset.ofHours(9)))))
                .isInstanceOf(DataAccessException.class);

        // Then: current_status upsert도 함께 롤백되어 행이 남지 않는다
        assertThat(vehicleCurrentStatusMapper.findByVehicleId(VEHICLE_ID)).isEmpty();

        // Then: status_history에도 새 이력이 남지 않는다
        Integer historyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vehicle_status_history WHERE vehicle_id = ?", Integer.class, VEHICLE_ID);
        assertThat(historyCount).isZero();
    }

    private void insertVehicleCommitted(String vehicleId) {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId + " 이름");
        vehicle.setSource(VehicleSource.SIMULATION);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);
    }

    /**
     * 이력 insert에서만 실제 {@link DataAccessException}을 던지는 테스트 전용 Mapper 구현을 {@code @Primary}로
     * 주입한다. 이 @TestConfiguration은 정적 중첩 클래스라 이 테스트 클래스의 컨텍스트에만 적용된다
     * (다른 테스트 클래스의 컨텍스트는 오염되지 않는다).
     */
    @TestConfiguration
    static class ThrowingHistoryMapperConfig {

        @Bean
        @Primary
        VehicleStatusHistoryMapper throwingVehicleStatusHistoryMapper() {
            return new VehicleStatusHistoryMapper() {
                @Override
                public int insert(VehicleStatusHistory history) {
                    // 실제 SQL 예외 계열(DataAccessException)을 그대로 던져, 운영에서 이력 insert가
                    // 실패했을 때와 동일하게 서비스의 @Transactional 롤백을 유발한다.
                    throw new DataIntegrityViolationException(
                            "forced vehicle_status_history insert failure (test only)");
                }

                @Override
                public List<VehicleStatusHistory> findRecentByVehicleId(String vehicleId, int limit) {
                    // 이 테스트에서는 조회에 쓰지 않는다(검증은 JdbcTemplate COUNT로 수행).
                    return List.of();
                }
            };
        }
    }
}
