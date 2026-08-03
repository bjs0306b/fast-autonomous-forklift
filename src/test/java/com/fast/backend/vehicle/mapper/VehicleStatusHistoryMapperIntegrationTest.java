package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleStatusHistoryMapperIntegrationTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 8, 1, 10, 0);

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleStatusHistoryMapper historyMapper;

    @Test
    void insert_assignsGeneratedHistoryId() {
        insertVehicle("HISTORY-INSERT");

        VehicleStatusHistory history = insertHistory("HISTORY-INSERT", VehicleStatus.MOVING, 87, BASE);

        assertThat(history.getHistoryId()).isNotNull();
        List<VehicleStatusHistory> stored =
                historyMapper.findByVehicleId("HISTORY-INSERT", null, null, null, 20, 0);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getStatus()).isEqualTo(VehicleStatus.MOVING);
        assertThat(stored.get(0).getBattery()).isEqualTo(87);
        assertThat(stored.get(0).getMessageAt()).isEqualTo(BASE);
        assertThat(stored.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void findByVehicleId_returnsLatestFirstAndOnlyThatVehicle() {
        insertVehicle("HISTORY-ORDER");
        insertVehicle("HISTORY-OTHER");
        insertHistory("HISTORY-ORDER", VehicleStatus.IDLE, 90, BASE);
        insertHistory("HISTORY-ORDER", VehicleStatus.MOVING, 88, BASE.plusMinutes(1));
        insertHistory("HISTORY-OTHER", VehicleStatus.ERROR, 10, BASE.plusMinutes(2));

        List<VehicleStatusHistory> stored =
                historyMapper.findByVehicleId("HISTORY-ORDER", null, null, null, 20, 0);

        assertThat(stored).extracting(VehicleStatusHistory::getStatus)
                .containsExactly(VehicleStatus.MOVING, VehicleStatus.IDLE);
    }

    /** 같은 message_at이면 나중에 들어온 이력(history_id가 큰 쪽)이 먼저 온다. */
    @Test
    void findByVehicleId_breaksTiesByHistoryIdDesc() {
        insertVehicle("HISTORY-TIE");
        VehicleStatusHistory first = insertHistory("HISTORY-TIE", VehicleStatus.IDLE, 90, BASE);
        VehicleStatusHistory second = insertHistory("HISTORY-TIE", VehicleStatus.MOVING, 89, BASE);

        List<VehicleStatusHistory> stored =
                historyMapper.findByVehicleId("HISTORY-TIE", null, null, null, 20, 0);

        assertThat(stored).extracting(VehicleStatusHistory::getHistoryId)
                .containsExactly(second.getHistoryId(), first.getHistoryId());
    }

    /** 기간 조건은 from 포함, to 제외다. */
    @Test
    void findByVehicleId_appliesHalfOpenRange() {
        insertVehicle("HISTORY-RANGE");
        insertHistory("HISTORY-RANGE", VehicleStatus.IDLE, 90, BASE.minusMinutes(1));
        insertHistory("HISTORY-RANGE", VehicleStatus.MOVING, 89, BASE);
        insertHistory("HISTORY-RANGE", VehicleStatus.LOADING, 88, BASE.plusMinutes(1));

        List<VehicleStatusHistory> stored = historyMapper.findByVehicleId(
                "HISTORY-RANGE", BASE, BASE.plusMinutes(1), null, 20, 0);

        assertThat(stored).extracting(VehicleStatusHistory::getStatus).containsExactly(VehicleStatus.MOVING);
        assertThat(historyMapper.countByVehicleId("HISTORY-RANGE", BASE, BASE.plusMinutes(1), null)).isEqualTo(1);
    }

    @Test
    void findByVehicleId_filtersByStatus() {
        insertVehicle("HISTORY-STATUS");
        insertHistory("HISTORY-STATUS", VehicleStatus.MOVING, 90, BASE);
        insertHistory("HISTORY-STATUS", VehicleStatus.IDLE, 89, BASE.plusMinutes(1));
        insertHistory("HISTORY-STATUS", VehicleStatus.MOVING, 88, BASE.plusMinutes(2));

        List<VehicleStatusHistory> stored =
                historyMapper.findByVehicleId("HISTORY-STATUS", null, null, VehicleStatus.MOVING, 20, 0);

        assertThat(stored).hasSize(2)
                .allSatisfy(history -> assertThat(history.getStatus()).isEqualTo(VehicleStatus.MOVING));
        assertThat(historyMapper.countByVehicleId("HISTORY-STATUS", null, null, VehicleStatus.MOVING)).isEqualTo(2);
    }

    @Test
    void findByVehicleId_appliesLimitAndOffset() {
        insertVehicle("HISTORY-PAGE");
        for (int i = 0; i < 5; i++) {
            insertHistory("HISTORY-PAGE", VehicleStatus.MOVING, 90 - i, BASE.plusMinutes(i));
        }

        List<VehicleStatusHistory> firstPage =
                historyMapper.findByVehicleId("HISTORY-PAGE", null, null, null, 2, 0);
        List<VehicleStatusHistory> secondPage =
                historyMapper.findByVehicleId("HISTORY-PAGE", null, null, null, 2, 2);

        // 최신순이므로 첫 페이지는 +4분·+3분, 둘째 페이지는 +2분·+1분이다.
        assertThat(firstPage).extracting(VehicleStatusHistory::getMessageAt)
                .containsExactly(BASE.plusMinutes(4), BASE.plusMinutes(3));
        assertThat(secondPage).extracting(VehicleStatusHistory::getMessageAt)
                .containsExactly(BASE.plusMinutes(2), BASE.plusMinutes(1));
        assertThat(historyMapper.countByVehicleId("HISTORY-PAGE", null, null, null)).isEqualTo(5);
    }

    /** 같은 상태가 반복되는 heartbeat도 UNIQUE 제약 없이 모두 쌓인다. */
    @Test
    void insert_keepsRepeatedHeartbeatsOfSameStatus() {
        insertVehicle("HISTORY-HEARTBEAT");
        insertHistory("HISTORY-HEARTBEAT", VehicleStatus.IDLE, 100, BASE);
        insertHistory("HISTORY-HEARTBEAT", VehicleStatus.IDLE, 100, BASE.plusSeconds(1));
        insertHistory("HISTORY-HEARTBEAT", VehicleStatus.IDLE, 100, BASE.plusSeconds(2));

        assertThat(historyMapper.countByVehicleId("HISTORY-HEARTBEAT", null, null, null)).isEqualTo(3);
    }

    @Test
    void countByVehicleId_returnsZeroWhenNoHistory() {
        insertVehicle("HISTORY-EMPTY");

        assertThat(historyMapper.countByVehicleId("HISTORY-EMPTY", null, null, null)).isZero();
        assertThat(historyMapper.findByVehicleId("HISTORY-EMPTY", null, null, null, 20, 0)).isEmpty();
    }

    private VehicleStatusHistory insertHistory(
            String vehicleId, VehicleStatus status, Integer battery, LocalDateTime messageAt) {
        VehicleStatusHistory history = new VehicleStatusHistory();
        history.setVehicleId(vehicleId);
        history.setStatus(status);
        history.setBattery(battery);
        history.setMessageAt(messageAt);
        history.setReceivedAt(messageAt.plusNanos(120_000_000));
        historyMapper.insert(history);
        return history;
    }

    private void insertVehicle(String vehicleId) {
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
