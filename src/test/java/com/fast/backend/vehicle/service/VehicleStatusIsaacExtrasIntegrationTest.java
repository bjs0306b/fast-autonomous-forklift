package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isaac 확장 필드의 DB 저장과 <b>보존 정책</b>(prompt32.md 1장 4번)을 실제 H2로 관통 검증한다.
 *
 * <p>가장 중요한 시나리오는 마지막 두 테스트다 — Isaac 메시지로 화물 정보가 저장된 뒤 ROS2 상태 메시지가
 * 같은 행을 갱신할 때 그 값이 <b>null로 지워지지 않아야</b> 한다. 이게 깨지면 관제 화면에서 화물을 싣고
 * 있던 차량이 ROS2 메시지 한 번에 화물 정보를 잃는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleStatusIsaacExtrasIntegrationTest {

    @Autowired
    private VehicleStatusService vehicleStatusService;

    @Autowired
    private VehicleStatusHistoryService vehicleStatusHistoryService;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCurrentStatusMapper currentStatusMapper;

    @Autowired
    private VehicleStatusHistoryMapper historyMapper;

    @Test
    void isaacStatus_persistsAllFiveExtensionFieldsToCurrentStatus() {
        insertVehicle("ISX-01");

        vehicleStatusService.updateCurrentStatus("ISX-01", isaacCommand(
                "MOVING", 87, 0.12, true, "BOX-0042", 1.2, 0.8, at(10, 0)));

        VehicleCurrentStatus current = currentStatusMapper.findByVehicleId("ISX-01").orElseThrow();
        assertThat(current.getStatus()).isEqualTo(VehicleStatus.MOVING);
        assertThat(current.getForkHeight()).isEqualTo(0.12);
        assertThat(current.getHasCargo()).isTrue();
        assertThat(current.getCargoId()).isEqualTo("BOX-0042");
        assertThat(current.getFootprintLength()).isEqualTo(1.2);
        assertThat(current.getFootprintWidth()).isEqualTo(0.8);
    }

    @Test
    void isaacStatus_persistsExtensionFieldsToHistoryToo() {
        insertVehicle("ISX-02");

        vehicleStatusService.updateCurrentStatus("ISX-02", isaacCommand(
                "LIFTING", 70, 0.35, true, "BOX-1", 1.2, 0.8, at(10, 0)));

        List<VehicleStatusHistory> history = historyMapper.findRecentByVehicleId("ISX-02", 10);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo(VehicleStatus.LIFTING);
        assertThat(history.get(0).getForkHeight()).isEqualTo(0.35);
        assertThat(history.get(0).getHasCargo()).isTrue();
        assertThat(history.get(0).getCargoId()).isEqualTo("BOX-1");
        assertThat(history.get(0).getFootprintLength()).isEqualTo(1.2);
        assertThat(history.get(0).getFootprintWidth()).isEqualTo(0.8);
    }

    @Test
    void statusHistoryApi_exposesExtensionFieldsAndSeoulOffset() {
        insertVehicle("ISX-03");
        vehicleStatusService.updateCurrentStatus("ISX-03", isaacCommand(
                "LOADING", 65, 0.5, true, "BOX-9", 1.0, 0.6, at(10, 0)));

        List<VehicleStatusHistoryResponse> rows =
                vehicleStatusHistoryService.findRecentHistory("ISX-03", 10);

        assertThat(rows).hasSize(1);
        VehicleStatusHistoryResponse row = rows.get(0);
        assertThat(row.status()).isEqualTo(VehicleStatus.LOADING);
        assertThat(row.forkHeight()).isEqualTo(0.5);
        assertThat(row.hasCargo()).isTrue();
        assertThat(row.cargoId()).isEqualTo("BOX-9");
        assertThat(row.footprintLength()).isEqualTo(1.0);
        assertThat(row.footprintWidth()).isEqualTo(0.6);
        assertThat(row.messageAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(row.receivedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void ros2Status_afterIsaacStatus_preservesExtensionFieldsInsteadOfNullingThem() {
        insertVehicle("ISX-04");
        vehicleStatusService.updateCurrentStatus("ISX-04", isaacCommand(
                "MOVING", 87, 0.12, true, "BOX-0042", 1.2, 0.8, at(10, 0)));

        // ROS2 상태 메시지 — Isaac 확장 필드를 담지 않는다(isaacExtras == null).
        vehicleStatusService.updateCurrentStatus("ISX-04",
                new VehicleStatusUpdateCommand("IDLE", 80, null, null, null, null, at(10, 1)));

        VehicleCurrentStatus current = currentStatusMapper.findByVehicleId("ISX-04").orElseThrow();
        assertThat(current.getStatus()).isEqualTo(VehicleStatus.IDLE);   // 상태는 갱신되고
        assertThat(current.getBattery()).isEqualTo(80);
        assertThat(current.getForkHeight()).isEqualTo(0.12);             // Isaac 값은 보존된다
        assertThat(current.getHasCargo()).isTrue();
        assertThat(current.getCargoId()).isEqualTo("BOX-0042");
        assertThat(current.getFootprintLength()).isEqualTo(1.2);
        assertThat(current.getFootprintWidth()).isEqualTo(0.8);
    }

    @Test
    void ros2Status_afterIsaacStatus_historyRowAlsoCarriesThePreservedValues() {
        // 이력의 각 행은 "그 메시지를 받은 시점에 시스템이 알고 있던 전체 상태"를 나타낸다.
        insertVehicle("ISX-05");
        vehicleStatusService.updateCurrentStatus("ISX-05", isaacCommand(
                "MOVING", 87, 0.12, true, "BOX-0042", 1.2, 0.8, at(10, 0)));
        vehicleStatusService.updateCurrentStatus("ISX-05",
                new VehicleStatusUpdateCommand("IDLE", 80, null, null, null, null, at(10, 1)));

        List<VehicleStatusHistory> history = historyMapper.findRecentByVehicleId("ISX-05", 10);
        assertThat(history).hasSize(2);
        VehicleStatusHistory newest = history.get(0);
        assertThat(newest.getStatus()).isEqualTo(VehicleStatus.IDLE);
        assertThat(newest.getCargoId()).isEqualTo("BOX-0042");
        assertThat(newest.getForkHeight()).isEqualTo(0.12);
    }

    @Test
    void isaacStatus_withClearedCargo_actuallyClearsTheStoredValue() {
        // Isaac 경로에서는 null도 유효한 갱신값이다 — 화물을 내려놓아 cargoId가 사라진 경우를 표현할 수
        // 있어야 하므로, 보존 정책이 Isaac 메시지까지 덮어버리면 안 된다.
        insertVehicle("ISX-06");
        vehicleStatusService.updateCurrentStatus("ISX-06", isaacCommand(
                "LOADING", 87, 0.12, true, "BOX-0042", 1.2, 0.8, at(10, 0)));

        vehicleStatusService.updateCurrentStatus("ISX-06", isaacCommand(
                "IDLE", 86, 0.0, false, null, 1.2, 0.8, at(10, 1)));

        VehicleCurrentStatus current = currentStatusMapper.findByVehicleId("ISX-06").orElseThrow();
        assertThat(current.getHasCargo()).isFalse();
        assertThat(current.getCargoId()).isNull();
        assertThat(current.getForkHeight()).isEqualTo(0.0);
    }

    @Test
    void ros2StatusOnly_leavesExtensionFieldsNullWhenNothingWasEverStored() {
        insertVehicle("ISX-07");

        vehicleStatusService.updateCurrentStatus("ISX-07",
                new VehicleStatusUpdateCommand("ACTIVE", 90, null, null, null, null, at(10, 0)));

        VehicleCurrentStatus current = currentStatusMapper.findByVehicleId("ISX-07").orElseThrow();
        assertThat(current.getForkHeight()).isNull();
        assertThat(current.getHasCargo()).isNull();
        assertThat(current.getCargoId()).isNull();
    }

    private OffsetDateTime at(int hour, int minute) {
        return OffsetDateTime.of(2026, 7, 23, hour, minute, 0, 0, ZoneOffset.ofHours(9));
    }

    private VehicleStatusUpdateCommand isaacCommand(
            String status, Integer battery, Double forkHeight, Boolean hasCargo, String cargoId,
            Double footprintLength, Double footprintWidth, OffsetDateTime messageAt) {
        return new VehicleStatusUpdateCommand(
                status, battery, null, null, null, null, messageAt,
                new VehicleStatusUpdateCommand.IsaacExtras(
                        forkHeight, hasCargo, cargoId, footprintLength, footprintWidth));
    }

    private void insertVehicle(String vehicleId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicleMapper.insert(vehicle);
    }
}
