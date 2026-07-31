package com.fast.backend.command.service;

import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * 안전 명령 command-result 반영 검증(prompt53.md 10·11·16장). 재사용하는 {@link VehicleCommandResultService}가
 * EMERGENCY_STOP 결과를 SUCCESS로 반영하고, vehicleId 불일치 거부·중복 멱등을 지키는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SafetyCommandResultIntegrationTest {

    @Autowired private SafetyCommandService safetyCommandService;
    @Autowired private VehicleCommandResultService vehicleCommandResultService;
    @Autowired private VehicleCommandService vehicleCommandService;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @MockBean private VehicleCommandPublisher publisher;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void emergencyStopResult_success_marksSucceeded() {
        doNothing().when(publisher).publish(any());
        register("SR-F01");
        VehicleCommandResponse issued = safetyCommandService.emergencyStop("SR-F01", null);

        vehicleCommandResultService.handleResult(estopResult(issued.commandId(), "SR-F01", "SUCCESS"));

        assertThat(vehicleCommandService.findByCommandId("SR-F01", issued.commandId()).status())
                .isEqualTo("SUCCESS");
    }

    @Test
    void result_vehicleIdMismatch_isRejected() {
        doNothing().when(publisher).publish(any());
        register("SR-F02");
        VehicleCommandResponse issued = safetyCommandService.emergencyStop("SR-F02", null);

        vehicleCommandResultService.handleResult(estopResult(issued.commandId(), "OTHER", "SUCCESS"));

        // 불일치 → 상태 변경 없음(PUBLISHED 유지)
        assertThat(vehicleCommandService.findByCommandId("SR-F02", issued.commandId()).status())
                .isEqualTo("PUBLISHED");
    }

    @Test
    void duplicateSuccess_isIdempotent() {
        doNothing().when(publisher).publish(any());
        register("SR-F03");
        VehicleCommandResponse issued = safetyCommandService.emergencyStop("SR-F03", null);

        vehicleCommandResultService.handleResult(estopResult(issued.commandId(), "SR-F03", "SUCCESS"));
        vehicleCommandResultService.handleResult(estopResult(issued.commandId(), "SR-F03", "SUCCESS")); // 중복

        assertThat(vehicleCommandService.findByCommandId("SR-F03", issued.commandId()).status())
                .isEqualTo("SUCCESS");
    }

    private VehicleCommandResultMessage estopResult(String commandId, String vehicleId, String result) {
        // EMERGENCY_STOP 확정 조합: targetSystem=ALL, commandCategory=SAFETY
        return new VehicleCommandResultMessage(commandId, vehicleId, "ALL", "SAFETY", "EMERGENCY_STOP", result,
                null, null, null, null, null, null, "msg", OffsetDateTime.now());
    }

    private void register(String vehicleId) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(VehicleSource.REAL);
        v.setActive(true);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);
        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(VehicleStatus.MOVING);
        cur.setReceivedAt(NOW);
        cur.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }
}
