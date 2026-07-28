package com.fast.backend.command.service;

import com.fast.backend.command.dto.EmergencyStopAllResponse;
import com.fast.backend.command.dto.SafetyCommandRequest;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 전체 비상정지 요청 바디 전달 검증(prompt56.md 11장, 16장 26~34번).
 *
 * <p>이전에는 Controller가 {@code SafetyCommandRequest}를 받고도 Service에 넘기지 않아 reason이 조용히
 * 버려졌다. 이제 reason이 <b>차량별 명령 각각</b>에 실려 나가는지, 바디 생략 시 기존 동작이 유지되는지,
 * 부분 실패·독립 commandId·요약 이벤트 같은 기존 보장이 그대로인지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmergencyStopAllRequestIntegrationTest {

    @Autowired private SafetyCommandService safetyCommandService;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    @Autowired private VehicleCommandMapper vehicleCommandMapper;

    @MockBean private VehicleCommandPublisher publisher;
    @MockBean private SimpMessagingTemplate messagingTemplate;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void emergencyStopAll_passesReasonToEveryVehicleCommand() {
        doNothing().when(publisher).publish(any());
        register("ESR1-F01", true, VehicleStatus.MOVING);
        register("ESR1-F02", true, VehicleStatus.ACTIVE);

        safetyCommandService.emergencyStopAll(new SafetyCommandRequest("전체 작업장 비상정지", "operator-01"));

        // MQTT 메시지에 동일한 reason이 차량마다 실린다
        ArgumentCaptor<VehicleCommandMessage> captor = ArgumentCaptor.forClass(VehicleCommandMessage.class);
        verify(publisher, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(
                m -> assertThat(m.reason()).isEqualTo("전체 작업장 비상정지"));

        // DB에 저장된 차량별 명령에도 reason이 남는다
        assertThat(latestCommandReason("ESR1-F01")).isEqualTo("전체 작업장 비상정지");
        assertThat(latestCommandReason("ESR1-F02")).isEqualTo("전체 작업장 비상정지");
    }

    @Test
    void emergencyStopAll_withBlankReason_storesNullNotEmptyString() {
        doNothing().when(publisher).publish(any());
        register("ESR2-F01", true, VehicleStatus.MOVING);

        safetyCommandService.emergencyStopAll(new SafetyCommandRequest("   ", "operator-01"));

        // reasonOrNull() 정책 — 공백만 있는 사유는 null로 정규화된다(단건 STOP/ESTOP과 동일)
        assertThat(latestCommandReason("ESR2-F01")).isNull();
    }

    @Test
    void emergencyStopAll_withNullBody_behavesAsBefore() {
        doNothing().when(publisher).publish(any());
        register("ESR3-F01", true, VehicleStatus.MOVING);

        EmergencyStopAllResponse summary = safetyCommandService.emergencyStopAll((SafetyCommandRequest) null);

        assertThat(summary.requestedCount()).isEqualTo(1);
        assertThat(summary.publishedCount()).isEqualTo(1);
        assertThat(latestCommandReason("ESR3-F01")).isNull();
    }

    @Test
    void emergencyStopAll_noArgOverload_stillWorks() {
        doNothing().when(publisher).publish(any());
        register("ESR4-F01", true, VehicleStatus.MOVING);

        EmergencyStopAllResponse summary = safetyCommandService.emergencyStopAll();

        assertThat(summary.requestedCount()).isEqualTo(1);
        assertThat(summary.publishedCount()).isEqualTo(1);
        assertThat(summary.failedCount()).isZero();
    }

    @Test
    void emergencyStopAll_continuesAfterOneVehicleFails_andKeepsCountsAndIndependentCommandIds() {
        doAnswer(inv -> {
            VehicleCommandMessage m = inv.getArgument(0);
            if ("ESR5-F02".equals(m.vehicleId())) {
                throw new RuntimeException("broker down");
            }
            return null;
        }).when(publisher).publish(any());
        register("ESR5-F01", true, VehicleStatus.MOVING);
        register("ESR5-F02", true, VehicleStatus.MOVING);
        register("ESR5-F03", true, VehicleStatus.MOVING);

        EmergencyStopAllResponse summary =
                safetyCommandService.emergencyStopAll(new SafetyCommandRequest("사유", "admin"));

        assertThat(summary.requestedCount()).isEqualTo(3);
        assertThat(summary.publishedCount()).isEqualTo(2);
        assertThat(summary.failedCount()).isEqualTo(1);
        // 실패한 차량 뒤의 차량도 계속 발행된다
        verify(publisher, times(3)).publish(any());
        // 차량별 commandId는 서로 독립
        assertThat(summary.results()).extracting(EmergencyStopAllResponse.Item::commandId)
                .filteredOn(Objects::nonNull).doesNotHaveDuplicates();
        assertThat(summary.results()).filteredOn(r -> r.vehicleId().equals("ESR5-F02"))
                .allSatisfy(r -> assertThat(r.status()).isEqualTo("PUBLISH_FAILED"));
        // 실패한 차량에도 reason은 그대로 저장된다(발행 실패와 사유 기록은 별개)
        assertThat(latestCommandReason("ESR5-F02")).isEqualTo("사유");
    }

    @Test
    void emergencyStopAll_stillBroadcastsGlobalSummaryEvent() {
        doNothing().when(publisher).publish(any());
        register("ESR6-F01", true, VehicleStatus.MOVING);

        safetyCommandService.emergencyStopAll(new SafetyCommandRequest("사유", "admin"));

        // 기존 WebSocket 요약 이벤트 계약 유지
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(eq("/topic/vehicles/emergency-stop"), any(Object.class));
    }

    @Test
    void emergencyStopAll_excludesInactiveVehicles() {
        doNothing().when(publisher).publish(any());
        register("ESR7-F01", true, VehicleStatus.MOVING);
        register("ESR7-F02", false, VehicleStatus.MOVING);

        EmergencyStopAllResponse summary =
                safetyCommandService.emergencyStopAll(new SafetyCommandRequest("사유", null));

        assertThat(summary.requestedCount()).isEqualTo(1);
        assertThat(summary.results()).extracting(EmergencyStopAllResponse.Item::vehicleId)
                .containsExactly("ESR7-F01");
    }

    // --- helpers ---

    private String latestCommandReason(String vehicleId) {
        List<com.fast.backend.command.domain.VehicleCommand> rows =
                vehicleCommandMapper.findRecentByVehicleId(vehicleId, 1, null);
        assertThat(rows).hasSize(1);
        return rows.get(0).getReason();
    }

    private void register(String vehicleId, boolean active, VehicleStatus status) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(VehicleSource.REAL);
        v.setActive(active);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);

        VehicleCurrentStatus cur = new VehicleCurrentStatus();
        cur.setVehicleId(vehicleId);
        cur.setStatus(status);
        cur.setReceivedAt(NOW);
        cur.setUpdatedAt(NOW);
        vehicleCurrentStatusMapper.upsert(cur);
    }
}
