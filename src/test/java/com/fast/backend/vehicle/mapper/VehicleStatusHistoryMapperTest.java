package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
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

/**
 * vehicle_status_history insert/조회가 실제 H2(MySQL 호환 모드)에서 동작하는지 검증한다(prompt22.md 8장).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleStatusHistoryMapperTest {

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleStatusHistoryMapper historyMapper;

    @Test
    void insert_singleRow_isReadableAfterward() {
        insertVehicle("HIST-01");

        historyMapper.insert(newHistory("HIST-01", VehicleStatus.ACTIVE, 80, LocalDateTime.now()));

        List<VehicleStatusHistory> rows = historyMapper.findRecentByVehicleId("HIST-01", 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(rows.get(0).getBattery()).isEqualTo(80);
    }

    @Test
    void insert_multipleRowsSameVehicle_accumulatesAllRows() {
        insertVehicle("HIST-02");
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(2);
        LocalDateTime t2 = LocalDateTime.now().minusMinutes(1);
        LocalDateTime t3 = LocalDateTime.now();

        historyMapper.insert(newHistory("HIST-02", VehicleStatus.IDLE, null, t1));
        historyMapper.insert(newHistory("HIST-02", VehicleStatus.ACTIVE, 90, t2));
        historyMapper.insert(newHistory("HIST-02", VehicleStatus.ERROR, 40, t3));

        List<VehicleStatusHistory> rows = historyMapper.findRecentByVehicleId("HIST-02", 10);
        assertThat(rows).hasSize(3);
    }

    @Test
    void findRecentByVehicleId_isSeparatedByVehicle() {
        insertVehicle("HIST-03");
        insertVehicle("HIST-04");
        historyMapper.insert(newHistory("HIST-03", VehicleStatus.IDLE, null, LocalDateTime.now()));
        historyMapper.insert(newHistory("HIST-04", VehicleStatus.ERROR, null, LocalDateTime.now()));

        List<VehicleStatusHistory> rows = historyMapper.findRecentByVehicleId("HIST-03", 10);

        assertThat(rows).extracting(VehicleStatusHistory::getVehicleId).containsOnly("HIST-03");
    }

    @Test
    void findRecentByVehicleId_ordersByMessageAtDescending() {
        insertVehicle("HIST-05");
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(2);
        LocalDateTime t2 = LocalDateTime.now().minusMinutes(1);
        LocalDateTime t3 = LocalDateTime.now();

        historyMapper.insert(newHistory("HIST-05", VehicleStatus.IDLE, null, t1));
        historyMapper.insert(newHistory("HIST-05", VehicleStatus.ACTIVE, null, t3));
        historyMapper.insert(newHistory("HIST-05", VehicleStatus.ERROR, null, t2));

        List<VehicleStatusHistory> rows = historyMapper.findRecentByVehicleId("HIST-05", 10);

        // DATETIME 컬럼은 저장 시 나노초 정밀도가 일부 손실될 수 있어(H2/MySQL 공통), 원본 LocalDateTime과의
        // 완전 동등 비교 대신 상태값으로 순서를 검증한다 — message_at 기준 최신순이면 ACTIVE(t3), ERROR(t2),
        // IDLE(t1) 순으로 나와야 한다.
        assertThat(rows).extracting(VehicleStatusHistory::getStatus)
                .containsExactly(VehicleStatus.ACTIVE, VehicleStatus.ERROR, VehicleStatus.IDLE);
    }

    @Test
    void findRecentByVehicleId_appliesLimit() {
        insertVehicle("HIST-06");
        for (int i = 0; i < 5; i++) {
            historyMapper.insert(newHistory("HIST-06", VehicleStatus.ACTIVE, null,
                    LocalDateTime.now().minusSeconds(i)));
        }

        List<VehicleStatusHistory> rows = historyMapper.findRecentByVehicleId("HIST-06", 2);

        assertThat(rows).hasSize(2);
    }

    @Test
    void insert_unknownStatus_mapsEnumCorrectly() {
        insertVehicle("HIST-07");

        historyMapper.insert(newHistory("HIST-07", VehicleStatus.UNKNOWN, null, LocalDateTime.now()));

        List<VehicleStatusHistory> rows = historyMapper.findRecentByVehicleId("HIST-07", 10);
        assertThat(rows.get(0).getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
    }

    @Test
    void insert_nullPositionAndBattery_storesNull() {
        insertVehicle("HIST-08");

        historyMapper.insert(newHistory("HIST-08", VehicleStatus.IDLE, null, LocalDateTime.now()));

        VehicleStatusHistory saved = historyMapper.findRecentByVehicleId("HIST-08", 1).get(0);
        assertThat(saved.getBattery()).isNull();
        assertThat(saved.getPositionX()).isNull();
        assertThat(saved.getPositionY()).isNull();
    }

    @Test
    void insert_generatesId() {
        insertVehicle("HIST-09");

        VehicleStatusHistory history = newHistory("HIST-09", VehicleStatus.ACTIVE, null, LocalDateTime.now());
        historyMapper.insert(history);

        assertThat(history.getId()).isNotNull();
    }

    private void insertVehicle(String vehicleId) {
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

    private VehicleStatusHistory newHistory(String vehicleId, VehicleStatus status, Integer battery,
            LocalDateTime messageAt) {
        VehicleStatusHistory history = new VehicleStatusHistory();
        history.setVehicleId(vehicleId);
        history.setStatus(status);
        history.setBattery(battery);
        history.setMessageAt(messageAt);
        history.setReceivedAt(LocalDateTime.now());
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }
}
