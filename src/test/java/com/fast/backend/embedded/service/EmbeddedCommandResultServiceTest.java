package com.fast.backend.embedded.service;

import com.fast.backend.embedded.domain.EmbeddedCommandStatus;
import com.fast.backend.embedded.domain.EmbeddedCommandType;
import com.fast.backend.embedded.domain.EmbeddedVehicleCommand;
import com.fast.backend.embedded.dto.EmbeddedCommandResultMessage;
import com.fast.backend.embedded.mapper.EmbeddedVehicleCommandMapper;
import com.fast.backend.vehicle.websocket.EmbeddedCommandResultEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code forklift/{id}/command-result} 처리 — commandId 조회, forkliftId/command 일치 검증,
 * {@link EmbeddedCommandStatus#canTransitionTo} 기반 상태 전이·중복 결과 거부, 비상정지 결과 저장을
 * 검증한다(prompt29.md 7장·16장·20장).
 */
class EmbeddedCommandResultServiceTest {

    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 7, 22, 10, 30, 0);

    private EmbeddedVehicleCommandMapper commandMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private EmbeddedCommandResultService service;

    @BeforeEach
    void setUp() {
        commandMapper = mock(EmbeddedVehicleCommandMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new EmbeddedCommandResultService(commandMapper, broadcaster);
    }

    private EmbeddedVehicleCommand existingCommand(EmbeddedCommandStatus status) {
        EmbeddedVehicleCommand entity = new EmbeddedVehicleCommand();
        entity.setCommandId("CMD-001");
        entity.setForkliftId("REAL01");
        entity.setCommand(EmbeddedCommandType.FORK_UP);
        entity.setStatus(status);
        return entity;
    }

    private EmbeddedCommandResultMessage resultMessage(String result) {
        return new EmbeddedCommandResultMessage(
                "CMD-001", "REAL01", "FORK_UP", result, "STOPPED", false,
                null, null, null, null, null, COMPLETED_AT);
    }

    @Test
    void handleResult_validSuccessFromPublished_updatesStatusAndBroadcasts() {
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.PUBLISHED);
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));

        service.handleResult(resultMessage("SUCCESS"));

        assertThat(existing.getStatus()).isEqualTo(EmbeddedCommandStatus.SUCCESS);
        assertThat(existing.getCompletedAt()).isEqualTo(COMPLETED_AT);
        verify(commandMapper, times(1)).update(existing);
        ArgumentCaptor<EmbeddedCommandResultEventData> captor =
                ArgumentCaptor.forClass(EmbeddedCommandResultEventData.class);
        verify(broadcaster).broadcastEmbeddedCommandResult(eq("REAL01"), captor.capture(), eq(COMPLETED_AT));
        assertThat(captor.getValue().result()).isEqualTo("SUCCESS");
    }

    @Test
    void handleResult_commandIdNotFound_skipsWithoutUpdate() {
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.empty());

        service.handleResult(resultMessage("SUCCESS"));

        verify(commandMapper, never()).update(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleResult_forkliftIdMismatch_skipsWithoutUpdate() {
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.PUBLISHED);
        existing.setForkliftId("REAL02"); // 저장된 명령은 REAL02 소유, 메시지는 REAL01
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));

        service.handleResult(resultMessage("SUCCESS"));

        verify(commandMapper, never()).update(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleResult_commandTypeMismatch_skipsWithoutUpdate() {
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.PUBLISHED);
        existing.setCommand(EmbeddedCommandType.STOP); // 저장된 명령은 STOP, 결과는 FORK_UP
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));

        service.handleResult(resultMessage("SUCCESS"));

        verify(commandMapper, never()).update(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleResult_unknownResultValue_skipsWithoutUpdate() {
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.PUBLISHED);
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));

        service.handleResult(resultMessage("NONSENSE"));

        verify(commandMapper, never()).update(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleResult_duplicateResultAfterAlreadyTerminal_skipsWithoutUpdate() {
        // 이미 SUCCESS인 명령에 같은 결과가 다시 오면 canTransitionTo가 거부한다(중복 결과 방어).
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.SUCCESS);
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));

        service.handleResult(resultMessage("SUCCESS"));

        verify(commandMapper, never()).update(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleResult_backwardTransition_skipsWithoutUpdate() {
        // 이미 IN_PROGRESS인데 ACCEPTED 결과가 오면 역행이라 거부된다.
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.IN_PROGRESS);
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));

        service.handleResult(resultMessage("ACCEPTED"));

        verify(commandMapper, never()).update(any());
        assertThat(existing.getStatus()).isEqualTo(EmbeddedCommandStatus.IN_PROGRESS);
    }

    @Test
    void handleResult_emergencyStopResult_savesStoppedActionsAndRequiresReset() {
        EmbeddedVehicleCommand existing = existingCommand(EmbeddedCommandStatus.PUBLISHED);
        existing.setCommand(EmbeddedCommandType.EMERGENCY_STOP);
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(existing));
        EmbeddedCommandResultMessage message = new EmbeddedCommandResultMessage(
                "CMD-001", "REAL01", "EMERGENCY_STOP", "SUCCESS", null, null,
                true, List.of("DRIVE", "STEERING", "FORK"), true, null, "비상정지 완료", COMPLETED_AT);

        service.handleResult(message);

        assertThat(existing.getStatus()).isEqualTo(EmbeddedCommandStatus.SUCCESS);
        assertThat(existing.getEmergencyStopApplied()).isTrue();
        assertThat(existing.getStoppedActions()).isEqualTo("DRIVE,STEERING,FORK");
        assertThat(existing.getRequiresReset()).isTrue();
        verify(commandMapper, times(1)).update(existing);
    }

    @Test
    void handleResult_nullCommandId_skipsWithoutCallingMapper() {
        EmbeddedCommandResultMessage message = new EmbeddedCommandResultMessage(
                null, "REAL01", "FORK_UP", "SUCCESS", null, null, null, null, null, null, null, COMPLETED_AT);

        service.handleResult(message);

        verifyNoInteractions(commandMapper);
        verifyNoInteractions(broadcaster);
    }

    @Test
    void handleResult_nullCompletedAt_skipsWithoutCallingMapper() {
        EmbeddedCommandResultMessage message = new EmbeddedCommandResultMessage(
                "CMD-001", "REAL01", "FORK_UP", "SUCCESS", null, null, null, null, null, null, null, null);

        service.handleResult(message);

        verifyNoInteractions(commandMapper);
    }

    @Test
    void handleResult_mapperThrowsRuntimeException_doesNotPropagate() {
        when(commandMapper.findByCommandId("CMD-001")).thenThrow(new RuntimeException("DB down"));

        assertThatCode(() -> service.handleResult(resultMessage("SUCCESS"))).doesNotThrowAnyException();
    }
}
