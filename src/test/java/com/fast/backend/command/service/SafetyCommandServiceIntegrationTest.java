package com.fast.backend.command.service;

import com.fast.backend.command.dto.EmergencyStopAllResponse;
import com.fast.backend.command.dto.SafetyCommandRequest;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.command.service.VehicleCommandPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 차량 안전 명령 발행 검증(prompt53.md 7·8·9장). 기존 VehicleCommand 도메인 재사용 위에서 STOP/EMERGENCY_STOP
 * 발행·부분실패·활성검증을 확인한다. 실제 발행은 {@link VehicleCommandPublisher} Mock으로 대체한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SafetyCommandServiceIntegrationTest {

    @Autowired private SafetyCommandService safetyCommandService;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @MockBean private VehicleCommandPublisher publisher;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void stop_activeVehicle_publishesStopForThatVehicleOnly() {
        doNothing().when(publisher).publish(any());
        register("SC-F01", true, VehicleStatus.MOVING);

        VehicleCommandResponse resp = safetyCommandService.stop("SC-F01", new SafetyCommandRequest("정지", "admin"));

        assertThat(resp.command()).isEqualTo("STOP");
        assertThat(resp.status()).isEqualTo("PUBLISHED");
        assertThat(resp.vehicleId()).isEqualTo("SC-F01");
        ArgumentCaptor<VehicleCommandMessage> c = ArgumentCaptor.forClass(VehicleCommandMessage.class);
        verify(publisher).publish(c.capture());
        assertThat(c.getValue().vehicleId()).isEqualTo("SC-F01");
        assertThat(c.getValue().command().name()).isEqualTo("STOP");
    }

    @Test
    void emergencyStop_doesNotForceCurrentStatusToEstop() {
        doNothing().when(publisher).publish(any());
        register("SC-F02", true, VehicleStatus.IDLE);

        VehicleCommandResponse resp = safetyCommandService.emergencyStop("SC-F02", new SafetyCommandRequest(null, null));

        assertThat(resp.command()).isEqualTo("EMERGENCY_STOP");
        assertThat(resp.status()).isEqualTo("PUBLISHED");
        // 발행만으로 차량 현재 상태를 ESTOP으로 강제 변경하지 않는다(§11).
        assertThat(vehicleCurrentStatusMapper.findByVehicleId("SC-F02").orElseThrow().getStatus())
                .isEqualTo(VehicleStatus.IDLE);
    }

    @Test
    void publishFailure_marksPublishFailed() {
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());
        register("SC-F03", true, VehicleStatus.MOVING);

        VehicleCommandResponse resp = safetyCommandService.stop("SC-F03", null);

        assertThat(resp.status()).isEqualTo("PUBLISH_FAILED");
    }

    @Test
    void inactiveVehicle_isRejected() {
        register("SC-F04", false, VehicleStatus.IDLE);
        assertThatThrownBy(() -> safetyCommandService.stop("SC-F04", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_INACTIVE);
    }

    @Test
    void unregisteredVehicle_isRejected() {
        assertThatThrownBy(() -> safetyCommandService.stop("SC-NONE", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void invalidVehicleId_withMqttWildcard_isRejected() {
        assertThatThrownBy(() -> safetyCommandService.stop("SC/+#", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VEHICLE_ID);
    }

    @Test
    void emergencyStopAll_perVehicleIndependentCommands_withPartialFailure() {
        // F02만 publish 실패하도록 구성 — 나머지 차량 발행은 계속되어야 한다.
        doAnswer(inv -> {
            VehicleCommandMessage m = inv.getArgument(0);
            if ("ESA-F02".equals(m.vehicleId())) {
                throw new RuntimeException("boom");
            }
            return null;
        }).when(publisher).publish(any());
        register("ESA-F01", true, VehicleStatus.MOVING);
        register("ESA-F02", true, VehicleStatus.MOVING);
        register("ESA-S01", true, VehicleStatus.ACTIVE); // SIM 포함

        EmergencyStopAllResponse summary = safetyCommandService.emergencyStopAll();

        assertThat(summary.requestedCount()).isEqualTo(3);
        assertThat(summary.publishedCount()).isEqualTo(2);
        assertThat(summary.failedCount()).isEqualTo(1);
        // 차량별 commandId가 모두 다르고(독립), 발행은 3회(한 건 실패 포함) 시도됨
        assertThat(summary.results()).extracting(EmergencyStopAllResponse.Item::commandId)
                .filteredOn(java.util.Objects::nonNull).doesNotHaveDuplicates();
        assertThat(summary.results()).anySatisfy(r -> {
            if (r.vehicleId().equals("ESA-F02")) {
                assertThat(r.status()).isEqualTo("PUBLISH_FAILED");
            }
        });
        verify(publisher, times(3)).publish(any());
    }

    @Test
    void emergencyStopAll_noActiveVehicles_returnsZero() {
        EmergencyStopAllResponse summary = safetyCommandService.emergencyStopAll();
        assertThat(summary.requestedCount()).isZero();
        assertThat(summary.results()).isEmpty();
    }

    private void register(String vehicleId, boolean active, VehicleStatus status) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setActive(active);
        v.setCreatedAt(NOW);
        vehicleMapper.insert(v);

        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(status);
        cur.setReceivedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }
}
