package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.command.websocket.VehicleCommandResultEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 명령 결과 수신 검증 순서(prompt32.md 1장 12번 "필수 검증" 8개)를 항목별로 고정한다.
 * 하위 호환(구 {@code forkliftId} 키, targetSystem/commandCategory 누락)도 함께 검증한다.
 */
class VehicleCommandResultServiceTest {

    private static final OffsetDateTime COMPLETED_AT =
            OffsetDateTime.of(2026, 7, 23, 11, 20, 28, 0, ZoneOffset.ofHours(9));

    private VehicleCommandMapper commandMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private VehicleCommandResultService service;

    @BeforeEach
    void setUp() {
        commandMapper = mock(VehicleCommandMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new VehicleCommandResultService(commandMapper, broadcaster);
    }

    @Test
    void handleResult_matchingEverything_updatesStatusAndBroadcasts() {
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(result("CMD-003", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS"));

        ArgumentCaptor<VehicleCommand> captor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VehicleCommandStatus.SUCCESS);
        assertThat(captor.getValue().getCompletedAt()).isNotNull();
        verify(broadcaster).broadcastCommandResult(eq("REAL-F01"), any(), eq(COMPLETED_AT));
    }

    @Test
    void handleResult_unknownCommandId_isSkipped() {
        when(commandMapper.findByCommandId("CMD-404")).thenReturn(Optional.empty());

        service.handleResult(result("CMD-404", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS"));

        verify(commandMapper, never()).update(any());
        verify(broadcaster, never()).broadcastCommandResult(any(), any(), any());
    }

    @Test
    void handleResult_vehicleIdMismatch_isSkipped() {
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(result("CMD-003", "SIM-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS"));

        verify(commandMapper, never()).update(any());
    }

    @Test
    void handleResult_targetSystemMismatch_isSkipped() {
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(result("CMD-003", "REAL-F01", "EMBEDDED", "SAFETY", "EMERGENCY_STOP", "SUCCESS"));

        verify(commandMapper, never()).update(any());
    }

    @Test
    void handleResult_commandCategoryMismatch_isSkipped() {
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(result("CMD-003", "REAL-F01", "ALL", "MOVE", "EMERGENCY_STOP", "SUCCESS"));

        verify(commandMapper, never()).update(any());
    }

    @Test
    void handleResult_commandMismatch_isSkipped() {
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(result("CMD-003", "REAL-F01", "ALL", "SAFETY", "RESET_ESTOP", "SUCCESS"));

        verify(commandMapper, never()).update(any());
    }

    @Test
    void handleResult_unknownResultValue_isSkipped() {
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(result("CMD-003", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "DONE"));

        verify(commandMapper, never()).update(any());
    }

    @Test
    void handleResult_duplicateTerminalResult_isSkippedBySameTransitionRule() {
        VehicleCommand alreadyDone = published();
        alreadyDone.setStatus(VehicleCommandStatus.SUCCESS);
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(alreadyDone));

        service.handleResult(result("CMD-003", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS"));

        verify(commandMapper, never()).update(any());
        verify(broadcaster, never()).broadcastCommandResult(any(), any(), any());
    }

    @Test
    void handleResult_missingRequiredField_isSkipped() {
        service.handleResult(new VehicleCommandResultMessage(
                null, "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS",
                null, null, null, null, null, null, null, COMPLETED_AT));

        verify(commandMapper, never()).findByCommandId(any());
    }

    @Test
    void handleResult_missingCompletedAt_isSkipped() {
        service.handleResult(new VehicleCommandResultMessage(
                "CMD-003", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS",
                null, null, null, null, null, null, null, null));

        verify(commandMapper, never()).findByCommandId(any());
    }

    @Test
    void handleResult_legacyFormatWithoutTargetSystem_isStillAcceptedForBackwardCompatibility() {
        // prompt32.md 3장 5번: 외부 담당자가 아직 이전 JSON을 쓰고 있을 수 있어 읽기 호환을 제공한다.
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(new VehicleCommandResultMessage(
                "CMD-003", "REAL-F01", null, null, "EMERGENCY_STOP", "SUCCESS",
                null, null, null, null, null, null, null, COMPLETED_AT));

        verify(commandMapper).update(any());
    }

    @Test
    void handleResult_legacyFormat_stillEmitsCompleteEnvelopeUsingStoredValues() {
        // 구 형식이라 payload에 targetSystem/commandCategory가 없어도, 발행 당시 저장해 둔 값으로 채워
        // 프론트에는 항상 완전한 envelope가 나가야 한다.
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(new VehicleCommandResultMessage(
                "CMD-003", "REAL-F01", null, null, "EMERGENCY_STOP", "SUCCESS",
                null, null, true, List.of("DRIVE", "FORK"), false, null, "정지 완료", COMPLETED_AT));

        ArgumentCaptor<VehicleCommandResultEventData> captor =
                ArgumentCaptor.forClass(VehicleCommandResultEventData.class);
        verify(broadcaster).broadcastCommandResult(eq("REAL-F01"), captor.capture(), any());
        assertThat(captor.getValue().targetSystem()).isEqualTo("ALL");
        assertThat(captor.getValue().commandCategory()).isEqualTo("SAFETY");
        assertThat(captor.getValue().emergencyStopApplied()).isTrue();
        assertThat(captor.getValue().stoppedActions()).containsExactly("DRIVE", "FORK");
    }

    @Test
    void handleResult_stoppedActions_areStoredVerbatimWithoutBackendGuessing() {
        // 백엔드가 실제 하드웨어 중단 범위를 추측하지 않는다 — 받은 값을 그대로 보존한다.
        when(commandMapper.findByCommandId("CMD-003")).thenReturn(Optional.of(published()));

        service.handleResult(new VehicleCommandResultMessage(
                "CMD-003", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS",
                null, null, true, List.of("DRIVE", "STEERING", "FORK"), true, null, null, COMPLETED_AT));

        ArgumentCaptor<VehicleCommand> captor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).update(captor.capture());
        assertThat(captor.getValue().getStoppedActions()).isEqualTo("DRIVE,STEERING,FORK");
        assertThat(captor.getValue().getRequiresReset()).isTrue();
    }

    @Test
    void handleResult_mapperThrows_exceptionDoesNotPropagateToMqttConsumer() {
        when(commandMapper.findByCommandId("CMD-003")).thenThrow(new RuntimeException("DB down"));

        service.handleResult(result("CMD-003", "REAL-F01", "ALL", "SAFETY", "EMERGENCY_STOP", "SUCCESS"));

        verify(broadcaster, never()).broadcastCommandResult(any(), any(), any());
    }

    private VehicleCommand published() {
        VehicleCommand command = new VehicleCommand();
        command.setCommandId("CMD-003");
        command.setVehicleId("REAL-F01");
        command.setCommand(VehicleCommandType.EMERGENCY_STOP);
        command.setTargetSystem(VehicleCommandTargetSystem.ALL);
        command.setCommandCategory(VehicleCommandCategory.SAFETY);
        command.setStatus(VehicleCommandStatus.PUBLISHED);
        return command;
    }

    private VehicleCommandResultMessage result(
            String commandId, String vehicleId, String targetSystem, String category, String command, String result) {
        return new VehicleCommandResultMessage(
                commandId, vehicleId, targetSystem, category, command, result,
                null, null, null, null, null, null, null, COMPLETED_AT);
    }
}
