package com.fast.backend.embedded.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.domain.EmbeddedCommandStatus;
import com.fast.backend.embedded.domain.EmbeddedVehicleCommand;
import com.fast.backend.embedded.dto.EmbeddedCommandResponse;
import com.fast.backend.embedded.dto.EmbeddedForkliftCommandMessage;
import com.fast.backend.embedded.mapper.EmbeddedVehicleCommandMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST 명령 발행(issueCommand)의 검증·저장·MQTT 발행 성공/실패 상태 분리, 조회 API를 검증한다
 * (prompt29.md 14장·16장). 실제 DB는 쓰지 않고 Mapper를 모킹한다(MapperTest가 실제 DB 검증을 담당).
 */
class EmbeddedCommandServiceTest {

    private VehicleMapper vehicleMapper;
    private EmbeddedVehicleCommandMapper commandMapper;
    private EmbeddedForkliftCommandPublisher publisher;
    private EmbeddedCommandService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        commandMapper = mock(EmbeddedVehicleCommandMapper.class);
        publisher = mock(EmbeddedForkliftCommandPublisher.class);
        service = new EmbeddedCommandService(vehicleMapper, commandMapper, publisher);
        when(vehicleMapper.findByVehicleId("REAL01")).thenReturn(Optional.of(mock(Vehicle.class)));
    }

    @Test
    void issueCommand_unregisteredVehicle_throwsVehicleNotFound() {
        when(vehicleMapper.findByVehicleId("REAL99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueCommand("REAL99", "FORK_UP", "test"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VEHICLE_NOT_FOUND);
        verify(commandMapper, never()).insert(any());
    }

    @Test
    void issueCommand_unknownCommand_throwsCommandTypeInvalid() {
        assertThatThrownBy(() -> service.issueCommand("REAL01", "FLY", "test"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_TYPE_INVALID);
        verify(commandMapper, never()).insert(any());
    }

    @Test
    void issueCommand_publishSucceeds_savesPublishedStatus() {
        EmbeddedCommandResponse response = service.issueCommand("REAL01", "FORK_UP", "화물 상차");

        assertThat(response.status()).isEqualTo(EmbeddedCommandStatus.PUBLISHED.name());
        assertThat(response.command()).isEqualTo("FORK_UP");
        assertThat(response.forkliftId()).isEqualTo("REAL01");
        assertThat(response.commandId()).isNotBlank();
        verify(commandMapper, times(1)).insert(any());
        verify(commandMapper, times(1)).update(any());
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void issueCommand_publishThrows_savesPublishFailedStatusWithoutRollback() {
        // MQTT 발행 실패해도 트랜잭션을 롤백하지 않고 PUBLISH_FAILED로 정직하게 저장한다(14장).
        doThrow(new RuntimeException("MQTT broker down")).when(publisher).publish(any());

        EmbeddedCommandResponse response = service.issueCommand("REAL01", "STOP", null);

        assertThat(response.status()).isEqualTo(EmbeddedCommandStatus.PUBLISH_FAILED.name());
        verify(commandMapper, times(1)).insert(any());
        verify(commandMapper, times(1)).update(any());
    }

    @Test
    void issueCommand_generatesUuidCommandId() {
        ArgumentCaptor<EmbeddedVehicleCommand> captor = ArgumentCaptor.forClass(EmbeddedVehicleCommand.class);

        service.issueCommand("REAL01", "STOP", null);

        verify(commandMapper).insert(captor.capture());
        assertThat(captor.getValue().getCommandId())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void issueCommand_publishesMessageWithSameCommandIdAsSavedEntity() {
        ArgumentCaptor<EmbeddedVehicleCommand> entityCaptor = ArgumentCaptor.forClass(EmbeddedVehicleCommand.class);
        ArgumentCaptor<EmbeddedForkliftCommandMessage> messageCaptor =
                ArgumentCaptor.forClass(EmbeddedForkliftCommandMessage.class);

        service.issueCommand("REAL01", "FORK_DOWN", "하차");

        verify(commandMapper).insert(entityCaptor.capture());
        verify(publisher).publish(messageCaptor.capture());
        assertThat(messageCaptor.getValue().commandId()).isEqualTo(entityCaptor.getValue().getCommandId());
        assertThat(messageCaptor.getValue().command()).isEqualTo("FORK_DOWN");
        assertThat(messageCaptor.getValue().reason()).isEqualTo("하차");
    }

    @Test
    void findByCommandId_notFound_throwsCommandNotFound() {
        when(commandMapper.findByCommandId("CMD-NONE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByCommandId("REAL01", "CMD-NONE"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_NOT_FOUND);
    }

    @Test
    void findByCommandId_forkliftIdMismatch_throwsCommandNotFound() {
        EmbeddedVehicleCommand entity = new EmbeddedVehicleCommand();
        entity.setCommandId("CMD-001");
        entity.setForkliftId("REAL02");
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.findByCommandId("REAL01", "CMD-001"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_NOT_FOUND);
    }

    @Test
    void findByCommandId_found_returnsResponseWithSplitStoppedActions() {
        EmbeddedVehicleCommand entity = new EmbeddedVehicleCommand();
        entity.setCommandId("CMD-001");
        entity.setForkliftId("REAL01");
        entity.setStoppedActions("DRIVE,FORK");
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(entity));

        EmbeddedCommandResponse response = service.findByCommandId("REAL01", "CMD-001");

        assertThat(response.stoppedActions()).containsExactly("DRIVE", "FORK");
    }

    @Test
    void findByCommandId_nullStoppedActions_returnsEmptyList() {
        EmbeddedVehicleCommand entity = new EmbeddedVehicleCommand();
        entity.setCommandId("CMD-001");
        entity.setForkliftId("REAL01");
        when(commandMapper.findByCommandId("CMD-001")).thenReturn(Optional.of(entity));

        EmbeddedCommandResponse response = service.findByCommandId("REAL01", "CMD-001");

        assertThat(response.stoppedActions()).isEmpty();
    }

    @Test
    void findRecentByForkliftId_limitTooLow_throwsLimitInvalid() {
        assertThatThrownBy(() -> service.findRecentByForkliftId("REAL01", 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_LIMIT_INVALID);
    }

    @Test
    void findRecentByForkliftId_limitTooHigh_throwsLimitInvalid() {
        assertThatThrownBy(() -> service.findRecentByForkliftId("REAL01", 201))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_LIMIT_INVALID);
    }

    @Test
    void findRecentByForkliftId_boundaryLimits_areAccepted() {
        when(commandMapper.findRecentByForkliftId(any(), anyInt())).thenReturn(List.of());

        assertThat(service.findRecentByForkliftId("REAL01", 1)).isEmpty();
        assertThat(service.findRecentByForkliftId("REAL01", 200)).isEmpty();
    }

    @Test
    void findRecentByForkliftId_unregisteredVehicle_throwsVehicleNotFound() {
        when(vehicleMapper.findByVehicleId("REAL99")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findRecentByForkliftId("REAL99", 50))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VEHICLE_NOT_FOUND);
    }
}
