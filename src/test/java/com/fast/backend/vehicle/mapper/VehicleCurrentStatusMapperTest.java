package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * vehicle_current_status의 upsert(ON DUPLICATE KEY UPDATE)와 상태별 집계 GROUP BY가 실제 H2(MySQL
 * 호환 모드)에서 정상 동작하는지 검증한다. MySQL 전용 문법(ON DUPLICATE KEY UPDATE)이라 이 테스트가
 * 통과한다는 것 자체가 schema.sql + Mapper XML 조합이 실제로 실행 가능하다는 근거가 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleCurrentStatusMapperTest {

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCurrentStatusMapper statusMapper;

    @Test
    void upsert_firstCall_insertsNewRow() {
        insertVehicle("STATUS-01");

        statusMapper.upsert(newStatus("STATUS-01", VehicleStatus.IDLE, null, LocalDateTime.now()));

        Optional<VehicleCurrentStatus> found = statusMapper.findByVehicleId("STATUS-01");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(VehicleStatus.IDLE);
    }

    @Test
    void upsert_secondCallSameVehicleId_updatesRowInPlace() {
        insertVehicle("STATUS-02");
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(1);
        LocalDateTime t2 = LocalDateTime.now();

        statusMapper.upsert(newStatus("STATUS-02", VehicleStatus.IDLE, null, t1));
        // 같은 vehicle_id로 다시 upsert — PK 충돌이 나지 않고(별도 INSERT였다면 예외가 나야 함)
        // 값만 덮어써야 한다.
        statusMapper.upsert(newStatus("STATUS-02", VehicleStatus.ACTIVE, 82, t2));

        VehicleCurrentStatus found = statusMapper.findByVehicleId("STATUS-02").orElseThrow();
        assertThat(found.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(found.getBattery()).isEqualTo(82);
    }

    @Test
    void findAllByVehicleIds_returnsOnlyRequestedIds() {
        insertVehicle("STATUS-03");
        insertVehicle("STATUS-04");
        statusMapper.upsert(newStatus("STATUS-03", VehicleStatus.IDLE, null, LocalDateTime.now()));
        statusMapper.upsert(newStatus("STATUS-04", VehicleStatus.ERROR, null, LocalDateTime.now()));

        List<VehicleCurrentStatus> result = statusMapper.findAllByVehicleIds(List.of("STATUS-03"));

        assertThat(result).extracting(VehicleCurrentStatus::getVehicleId).containsExactly("STATUS-03");
    }

    @Test
    void countByStatusForActiveVehicles_treatsMissingStatusAsUnknown_andExcludesInactive() {
        // A: 활성, 상태 없음 -> UNKNOWN으로 집계돼야 함
        insertVehicle("COUNT-A");
        // B: 활성, ACTIVE 상태
        insertVehicle("COUNT-B");
        statusMapper.upsert(newStatus("COUNT-B", VehicleStatus.ACTIVE, null, LocalDateTime.now()));
        // C: 비활성, ERROR 상태 -> 집계에서 완전히 제외돼야 함
        insertVehicle("COUNT-C", false);
        statusMapper.upsert(newStatus("COUNT-C", VehicleStatus.ERROR, null, LocalDateTime.now()));

        List<VehicleStatusCountRow> rows = statusMapper.countByStatusForActiveVehicles();

        assertThat(rows).extracting(VehicleStatusCountRow::getStatus).doesNotContain("ERROR");
        long unknownCount = rows.stream()
                .filter(r -> "UNKNOWN".equals(r.getStatus()))
                .mapToLong(VehicleStatusCountRow::getCount)
                .sum();
        long activeCount = rows.stream()
                .filter(r -> "ACTIVE".equals(r.getStatus()))
                .mapToLong(VehicleStatusCountRow::getCount)
                .sum();
        assertThat(unknownCount).isEqualTo(1);
        assertThat(activeCount).isEqualTo(1);
    }

    private Vehicle insertVehicle(String vehicleId) {
        return insertVehicle(vehicleId, true);
    }

    private Vehicle insertVehicle(String vehicleId, boolean active) {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId + " 이름");
        vehicle.setActive(active);
        vehicle.setCreatedAt(now);
        vehicleMapper.insert(vehicle);
        return vehicle;
    }

    private VehicleCurrentStatus newStatus(String vehicleId, VehicleStatus status, Integer battery,
            LocalDateTime messageAt) {
        VehicleCurrentStatus s = new VehicleCurrentStatus();
        s.setVehicleId(vehicleId);
        s.setStatus(status);
        s.setBattery(battery);
        s.setMessageAt(messageAt);
        s.setReceivedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }
}
