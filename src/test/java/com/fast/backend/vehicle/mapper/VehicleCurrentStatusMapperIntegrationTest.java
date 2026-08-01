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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleCurrentStatusMapperIntegrationTest {

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCurrentStatusMapper statusMapper;

    @Test
    void locationBeforeStatus_createsUnknownStatusRow() {
        insertVehicle("FORKLIFT-LOCATION-FIRST");
        LocalDateTime sourceTime = LocalDateTime.of(2026, 8, 1, 10, 0);

        statusMapper.updateLocationIfNewer(
                "FORKLIFT-LOCATION-FIRST", 1.2, 3.4, "map", 90.0, 0.4,
                sourceTime, sourceTime.plusSeconds(1));

        VehicleCurrentStatus stored = statusMapper.findByVehicleId("FORKLIFT-LOCATION-FIRST").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
        assertThat(stored.getPositionX()).isEqualTo(1.2);
        assertThat(stored.getPositionFrame()).isEqualTo("map");
        assertThat(stored.getMessageAt()).isEqualTo(sourceTime);
    }

    @Test
    void olderLocation_doesNotOverwriteLatestLocation() {
        insertVehicle("FORKLIFT-STALE");
        LocalDateTime latest = LocalDateTime.of(2026, 8, 1, 10, 0);

        statusMapper.updateLocationIfNewer(
                "FORKLIFT-STALE", 5.0, 6.0, "map", 180.0, 0.5,
                latest, latest.plusSeconds(1));
        statusMapper.updateLocationIfNewer(
                "FORKLIFT-STALE", 1.0, 2.0, "odom", 10.0, 0.1,
                latest.minusSeconds(1), latest.plusSeconds(2));

        VehicleCurrentStatus stored = statusMapper.findByVehicleId("FORKLIFT-STALE").orElseThrow();
        assertThat(stored.getPositionX()).isEqualTo(5.0);
        assertThat(stored.getPositionY()).isEqualTo(6.0);
        assertThat(stored.getPositionFrame()).isEqualTo("map");
        assertThat(stored.getHeading()).isEqualTo(180.0);
        assertThat(stored.getMessageAt()).isEqualTo(latest);
    }

    @Test
    void statusUpsert_preservesLocationAndSourceTime() {
        insertVehicle("FORKLIFT-STATUS");
        LocalDateTime sourceTime = LocalDateTime.of(2026, 8, 1, 10, 0);
        statusMapper.updateLocationIfNewer(
                "FORKLIFT-STATUS", 7.0, 8.0, "map", 270.0, 0.0,
                sourceTime, sourceTime.plusSeconds(1));

        VehicleCurrentStatus status = new VehicleCurrentStatus();
        status.setVehicleId("FORKLIFT-STATUS");
        status.setStatus(VehicleStatus.IDLE);
        status.setBattery(88);
        status.setReceivedAt(sourceTime.plusSeconds(2));
        statusMapper.upsert(status);

        VehicleCurrentStatus stored = statusMapper.findByVehicleId("FORKLIFT-STATUS").orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(VehicleStatus.IDLE);
        assertThat(stored.getBattery()).isEqualTo(88);
        assertThat(stored.getPositionX()).isEqualTo(7.0);
        assertThat(stored.getPositionY()).isEqualTo(8.0);
        assertThat(stored.getMessageAt()).isEqualTo(sourceTime);
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
