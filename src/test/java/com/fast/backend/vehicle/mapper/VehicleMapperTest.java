package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.Vehicle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * db/schema.sql(H2 MySQL 호환 모드)과 VehicleMapper.xml이 실제로 맞물려 동작하는지 검증하는
 * 통합 테스트. 실제 MySQL(EC2)에는 접속하지 않는다 — application-test.yml의 임베디드 H2를 사용한다.
 * 각 테스트는 클래스 트랜잭션 롤백으로 서로 독립적이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleMapperTest {

    @Autowired
    private VehicleMapper vehicleMapper;

    @Test
    void insert_generatesIdAndPersistsAllFields() {
        Vehicle vehicle = newVehicle("TEST-M01", "테스트 차량 1호");

        vehicleMapper.insert(vehicle);

        assertThat(vehicle.getId()).isNotNull();

        Optional<Vehicle> found = vehicleMapper.findByVehicleId("TEST-M01");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스트 차량 1호");
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    void findByVehicleId_unknownId_returnsEmpty() {
        assertThat(vehicleMapper.findByVehicleId("NO-SUCH-ID")).isEmpty();
    }

    @Test
    void existsByVehicleId_reflectsInsertedState() {
        assertThat(vehicleMapper.existsByVehicleId("TEST-M02")).isFalse();

        vehicleMapper.insert(newVehicle("TEST-M02", "테스트 차량 2호"));

        assertThat(vehicleMapper.existsByVehicleId("TEST-M02")).isTrue();
    }

    @Test
    void insert_duplicateVehicleId_violatesUniqueConstraint() {
        vehicleMapper.insert(newVehicle("TEST-DUP", "1호"));

        assertThrows(DataIntegrityViolationException.class,
                () -> vehicleMapper.insert(newVehicle("TEST-DUP", "2호")));
    }

    @Test
    void findAllActive_excludesInactiveVehicles() {
        Vehicle active = newVehicle("TEST-ACTIVE", "활성 차량");
        Vehicle inactive = newVehicle("TEST-INACTIVE", "비활성 차량");
        inactive.setActive(false);
        vehicleMapper.insert(active);
        vehicleMapper.insert(inactive);

        List<Vehicle> result = vehicleMapper.findAllActive();

        assertThat(result).extracting(Vehicle::getVehicleId).contains("TEST-ACTIVE");
        assertThat(result).extracting(Vehicle::getVehicleId).doesNotContain("TEST-INACTIVE");
    }

    @Test
    void updateActive_changesValueAndUpdatedAt() {
        Vehicle vehicle = newVehicle("TEST-UPDATE-ACTIVE", "활성 변경 차량");
        vehicleMapper.insert(vehicle);
        LocalDateTime changedAt = vehicle.getUpdatedAt().plusSeconds(1).withNano(0);

        int affected = vehicleMapper.updateActive(vehicle.getVehicleId(), false, changedAt);

        Vehicle found = vehicleMapper.findByVehicleId(vehicle.getVehicleId()).orElseThrow();
        assertThat(affected).isEqualTo(1);
        assertThat(found.isActive()).isFalse();
        assertThat(found.getUpdatedAt()).isEqualTo(changedAt);
    }

    private Vehicle newVehicle(String vehicleId, String name) {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(name);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        return vehicle;
    }
}
