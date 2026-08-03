package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * 이력 INSERT가 실패하면 최신 상태 upsert까지 함께 되돌아가는지(부분 성공 금지) 실제 트랜잭션에서 확인한다.
 *
 * <p>이 테스트에는 <b>클래스 레벨 {@code @Transactional}을 붙이지 않았다</b> — 테스트가 트랜잭션을 열면
 * {@code updateCurrentStatus}가 거기에 참여만 하게 되어 "실제로 롤백됐는지"를 DB에서 관찰할 수 없기
 * 때문이다. 대신 {@link TransactionTemplate}으로 트랜잭션 경계를 직접 만들어 롤백 결과를 확인한다.
 *
 * <p>준비용으로 넣은 차량 행 하나는 커밋된 채 남는다(H2 인메모리, 다른 테스트는 각자 고유한 vehicleId를
 * 쓰고 전체 건수를 단언하지 않아 영향이 없다).
 */
@SpringBootTest
@ActiveProfiles("test")
class VehicleStatusHistoryTransactionIntegrationTest {

    private static final String VEHICLE_ID = "HISTORY-ROLLBACK";

    @Autowired
    private VehicleStatusService vehicleStatusService;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCurrentStatusMapper statusMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private VehicleStatusHistoryMapper historyMapper;

    @Test
    void historyInsertFailure_rollsBackCurrentStatusUpsertToo() {
        insertVehicle(VEHICLE_ID);
        org.mockito.Mockito.doThrow(new IllegalStateException("history insert failed"))
                .when(historyMapper).insert(any());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                vehicleStatusService.updateCurrentStatus(
                        VEHICLE_ID,
                        new VehicleStatusUpdateCommand("MOVING", 87,
                                OffsetDateTime.of(2026, 8, 3, 9, 0, 0, 0, ZoneOffset.ofHours(9))))))
                .isInstanceOf(IllegalStateException.class);

        // 이력이 실패했으므로 최신 상태도 남아 있으면 안 된다(부분 성공 금지).
        assertThat(statusMapper.findByVehicleId(VEHICLE_ID)).isEmpty();
    }

    private void insertVehicle(String vehicleId) {
        if (vehicleMapper.existsByVehicleId(vehicleId)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);
    }
}
