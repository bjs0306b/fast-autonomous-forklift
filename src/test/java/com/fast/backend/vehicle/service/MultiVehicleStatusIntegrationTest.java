package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleDetailResponse;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleMapper;
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
 * 다중 차량 현재 상태 분리 검증(prompt51.md 3장). vehicleId별 독립 저장, 상호 무영향, stale 무시,
 * 목록/단건 조회 분리를 실제 H2로 확인한다.
 *
 * <p><b>주의</b>: prompt 예시의 {@code STOPPED}는 이 프로젝트 {@code VehicleStatus} enum에 없다
 * (UNKNOWN/IDLE/ACTIVE/MOVING/LIFTING/LOADING/UNLOADING/ESTOP/ERROR/OFFLINE). §1·§12 지침에 따라 운영
 * enum을 바꾸지 않고 유효 상태값(ACTIVE 등)으로 대체해 검증한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiVehicleStatusIntegrationTest {

    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleStatusService vehicleStatusService;
    @Autowired private VehicleService vehicleService;

    private static final OffsetDateTime T10 = OffsetDateTime.of(2026, 7, 27, 15, 0, 10, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime T5 = OffsetDateTime.of(2026, 7, 27, 15, 0, 5, 0, ZoneOffset.ofHours(9));

    @Test
    void eachVehicleStatusStoredIndependently() {
        register("REAL-F01");
        register("REAL-F02");
        register("SIM-F01");

        setStatus("REAL-F01", "MOVING", 81);
        setStatus("REAL-F02", "IDLE", 64);
        setStatus("SIM-F01", "ACTIVE", 100); // 예시의 STOPPED 대신 유효 enum 사용

        assertStatus("REAL-F01", VehicleStatus.MOVING, 81);
        assertStatus("REAL-F02", VehicleStatus.IDLE, 64);
        assertStatus("SIM-F01", VehicleStatus.ACTIVE, 100);
    }

    @Test
    void updatingOneVehicleDoesNotAffectAnother() {
        register("REAL-F01");
        register("REAL-F02");
        setStatus("REAL-F01", "MOVING", 81);
        setStatus("REAL-F02", "IDLE", 64);

        setStatus("REAL-F01", "ERROR", 10); // F01만 변경

        assertStatus("REAL-F01", VehicleStatus.ERROR, 10);
        assertStatus("REAL-F02", VehicleStatus.IDLE, 64); // F02 그대로
    }

    @Test
    void realAndSimStatusesDoNotMix() {
        register("REAL-F01");
        register("SIM-F01");
        setStatus("REAL-F01", "MOVING", 81);
        setStatus("SIM-F01", "ACTIVE", 100);

        assertStatus("REAL-F01", VehicleStatus.MOVING, 81);
        assertStatus("SIM-F01", VehicleStatus.ACTIVE, 100);
    }

    @Test
    void listReturnsEachVehicleExactlyOnce_andSingleQueryIsIsolated() {
        register("REAL-F01");
        register("REAL-F02");
        register("SIM-F01");
        setStatus("REAL-F01", "MOVING", 81);
        setStatus("REAL-F02", "IDLE", 64);
        setStatus("SIM-F01", "ACTIVE", 100);

        List<VehicleResponse> all = vehicleService.findActiveVehicles();
        assertThat(all).extracting(VehicleResponse::vehicleId)
                .containsExactlyInAnyOrder("REAL-F01", "REAL-F02", "SIM-F01");
        assertThat(all).hasSize(3);

        // 단건 조회가 다른 차량 정보를 반환하지 않음
        assertThat(vehicleService.getDetail("REAL-F02").status().status()).isEqualTo(VehicleStatus.IDLE);
    }

    @Test
    void staleMessageIsIgnored_andOnlyForThatVehicle() {
        register("REAL-F01");
        register("REAL-F02");
        setStatusAt("REAL-F01", "MOVING", 81, T10);
        setStatus("REAL-F02", "IDLE", 64);

        setStatusAt("REAL-F01", "ERROR", 5, T5); // 더 오래된 messageAt → 무시

        assertStatus("REAL-F01", VehicleStatus.MOVING, 81); // 최신 유지
        assertStatus("REAL-F02", VehicleStatus.IDLE, 64);   // 무영향
    }

    // --- helpers ---

    private void register(String vehicleId) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setActive(true);
        LocalDateTime now = LocalDateTime.now();
        v.setCreatedAt(now);
        vehicleMapper.insert(v);
    }

    private void setStatus(String vehicleId, String status, int battery) {
        setStatusAt(vehicleId, status, battery, OffsetDateTime.now(ZoneOffset.ofHours(9)));
    }

    private void setStatusAt(String vehicleId, String status, int battery, OffsetDateTime messageAt) {
        vehicleStatusService.updateCurrentStatus(vehicleId,
                new VehicleStatusUpdateCommand(status, battery, null, null, null, null, messageAt));
    }

    private void assertStatus(String vehicleId, VehicleStatus status, int battery) {
        VehicleDetailResponse d = vehicleService.getDetail(vehicleId);
        assertThat(d.status().status()).isEqualTo(status);
        assertThat(d.status().battery()).isEqualTo(battery);
    }
}
