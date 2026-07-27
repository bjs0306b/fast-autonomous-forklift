package com.fast.backend.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 통합 명령 발행 흐름(prompt32.md 1장 7~11번, 3장 2번)을 검증한다.
 *
 * <p>핵심 관심사 세 가지:
 * <ol>
 *   <li>백엔드가 commandId/timestamp/조합을 <b>스스로</b> 채운다(요청자가 넣지 않는다).</li>
 *   <li>잘못된 조합은 <b>발행하지 않고</b> 400으로 거부한다.</li>
 *   <li>MQTT 발행 실패를 <b>실행 성공으로 저장하지 않는다</b>(PUBLISH_FAILED로 정직하게 기록).</li>
 * </ol>
 */
class VehicleCommandServiceTest {

    private VehicleMapper vehicleMapper;
    private VehicleCommandMapper commandMapper;
    private VehicleCommandPublisher publisher;
    private VehicleCommandService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        commandMapper = mock(VehicleCommandMapper.class);
        publisher = mock(VehicleCommandPublisher.class);
        service = new VehicleCommandService(vehicleMapper, commandMapper, publisher, new ObjectMapper());
        when(vehicleMapper.findByVehicleId("REAL-F01")).thenReturn(Optional.of(vehicle("REAL-F01")));
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01")));
    }

    @Test
    void issueCommand_forkUp_usesConfirmedEmbeddedForkCombination() {
        VehicleCommandResponse response = service.issueCommand(
                "REAL-F01", new VehicleCommandRequest("FORK_UP", null, null, null, "적재 준비"));

        VehicleCommandMessage published = capturePublished();
        assertThat(published.command()).isEqualTo(VehicleCommandType.FORK_UP);
        assertThat(published.targetSystem()).isEqualTo(VehicleCommandTargetSystem.EMBEDDED);
        assertThat(published.commandCategory()).isEqualTo(VehicleCommandCategory.FORK);
        assertThat(published.reason()).isEqualTo("적재 준비");
        assertThat(response.status()).isEqualTo(VehicleCommandStatus.PUBLISHED.name());
    }

    @Test
    void issueCommand_load_usesConfirmedEmbeddedLoadCombination() {
        service.issueCommand("REAL-F01", new VehicleCommandRequest("LOAD", null, null, null, null));

        VehicleCommandMessage published = capturePublished();
        assertThat(published.targetSystem()).isEqualTo(VehicleCommandTargetSystem.EMBEDDED);
        assertThat(published.commandCategory()).isEqualTo(VehicleCommandCategory.LOAD);
    }

    @Test
    void issueCommand_emergencyStop_usesAllAndSafetySoReceiversCanBranchFirst() {
        service.issueCommand("REAL-F01",
                new VehicleCommandRequest("EMERGENCY_STOP", null, null, null, "관제 사용자 비상 정지"));

        VehicleCommandMessage published = capturePublished();
        assertThat(published.targetSystem()).isEqualTo(VehicleCommandTargetSystem.ALL);
        assertThat(published.commandCategory()).isEqualTo(VehicleCommandCategory.SAFETY);
        assertThat(published.command()).isEqualTo(VehicleCommandType.EMERGENCY_STOP);
    }

    @Test
    void issueCommand_move_requiresDestinationAndNormalisesHeadingAndFrameId() {
        service.issueCommand("SIM-F01", new VehicleCommandRequest(
                "MOVE", null, null, new VehicleCommandDestination(5.0, 6.0, -180.0, null), null));

        VehicleCommandMessage published = capturePublished();
        assertThat(published.targetSystem()).isEqualTo(VehicleCommandTargetSystem.ROS2);
        assertThat(published.commandCategory()).isEqualTo(VehicleCommandCategory.MOVE);
        VehicleCommandDestination destination = published.payload().destination();
        assertThat(destination.x()).isEqualTo(5.0);
        assertThat(destination.y()).isEqualTo(6.0);
        assertThat(destination.heading()).isEqualTo(180.0);   // -180 → [0,360) 정규화
        assertThat(destination.frameId()).isEqualTo("map");   // 생략 시 기본값
    }

    @Test
    void issueCommand_move_withoutDestination_isRejectedAndNeverPublished() {
        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("SIM-F01", new VehicleCommandRequest("MOVE", null, null, null, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_DESTINATION_INVALID);
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_move_nonFiniteDestination_isRejected() {
        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("SIM-F01", new VehicleCommandRequest(
                        "MOVE", null, null, new VehicleCommandDestination(Double.NaN, 1.0, 0.0, "map"), null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_DESTINATION_INVALID);
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_move_odomFrameIdIsAllowed() {
        service.issueCommand("SIM-F01", new VehicleCommandRequest(
                "MOVE", null, null, new VehicleCommandDestination(1.0, 2.0, 0.0, "odom"), null));

        assertThat(capturePublished().payload().destination().frameId()).isEqualTo("odom");
    }

    @Test
    void issueCommand_move_unsupportedFrameIdIsRejected() {
        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("SIM-F01", new VehicleCommandRequest(
                        "MOVE", null, null, new VehicleCommandDestination(1.0, 2.0, 0.0, "base_link"), null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_DESTINATION_INVALID);
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_explicitlyWrongTargetSystem_isRejectedInsteadOfSilentlyCorrected() {
        // 호출자가 잘못 알고 있는 조합을 조용히 고쳐서 발행하면, 수신 측이 targetSystem으로 1차 분기하는
        // 설계 자체가 신뢰를 잃는다.
        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("REAL-F01",
                        new VehicleCommandRequest("FORK_UP", "ROS2", "FORK", null, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_COMBINATION_INVALID);
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_explicitlyWrongCategory_isRejected() {
        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("REAL-F01",
                        new VehicleCommandRequest("EMERGENCY_STOP", "ALL", "MOVE", null, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_COMBINATION_INVALID);
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_explicitlyCorrectCombination_isAccepted() {
        service.issueCommand("REAL-F01",
                new VehicleCommandRequest("FORK_UP", "EMBEDDED", "FORK", null, null));

        assertThat(capturePublished().command()).isEqualTo(VehicleCommandType.FORK_UP);
    }

    @Test
    void issueCommand_unknownCommand_isRejected() {
        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("REAL-F01", new VehicleCommandRequest("LIFT", null, null, null, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_TYPE_INVALID);
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_unregisteredVehicle_isRejectedBeforeAnyInsert() {
        when(vehicleMapper.findByVehicleId("GHOST")).thenReturn(Optional.empty());

        BusinessException exception = catchThrowableOfType(
                () -> service.issueCommand("GHOST", new VehicleCommandRequest("STOP", null, null, null, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verify(commandMapper, never()).insert(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void issueCommand_generatesCommandIdAndTimestampItself() {
        // REST 요청 DTO에는 commandId/timestamp 자리가 아예 없다(3장 2번) — 백엔드가 채운다.
        VehicleCommandResponse response = service.issueCommand(
                "REAL-F01", new VehicleCommandRequest("STOP", null, null, null, null));

        assertThat(response.commandId()).isNotBlank();
        assertThat(UUID.fromString(response.commandId())).isNotNull();   // UUID 형식이어야 한다
        assertThat(response.issuedAt()).isNotNull();
        assertThat(response.issuedAt().getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(9));

        VehicleCommandMessage published = capturePublished();
        assertThat(published.commandId()).isEqualTo(response.commandId());
        assertThat(published.timestamp().getOffset()).isEqualTo(java.time.ZoneOffset.ofHours(9));
    }

    @Test
    void issueCommand_publishFails_isStoredAsPublishFailedNotAsSuccess() {
        doThrow(new RuntimeException("broker down")).when(publisher).publish(any());

        VehicleCommandResponse response = service.issueCommand(
                "REAL-F01", new VehicleCommandRequest("EMERGENCY_STOP", null, null, null, null));

        assertThat(response.status()).isEqualTo(VehicleCommandStatus.PUBLISH_FAILED.name());
        // insert(PENDING) 후 update(PUBLISH_FAILED)까지 저장은 그대로 유지된다(롤백하지 않음).
        verify(commandMapper).insert(any());
        ArgumentCaptor<VehicleCommand> captor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).update(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VehicleCommandStatus.PUBLISH_FAILED);
        assertThat(captor.getValue().getPublishedAt()).isNull();
    }

    @Test
    void issueCommand_insertsPendingThenUpdatesToPublished() {
        // Service는 같은 엔티티 인스턴스를 insert → 발행 → update로 재사용하므로, ArgumentCaptor로는
        // insert 시점의 상태를 볼 수 없다(호출 후 객체가 이미 변형돼 있다). 호출되는 그 순간의 값을
        // 기록해야 "PENDING으로 먼저 저장한다"는 순서를 실제로 검증할 수 있다.
        java.util.List<VehicleCommandStatus> statusAtInsert = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            statusAtInsert.add(invocation.getArgument(0, VehicleCommand.class).getStatus());
            return null;
        }).when(commandMapper).insert(any());

        service.issueCommand("REAL-F01", new VehicleCommandRequest("FORK_DOWN", null, null, null, null));

        assertThat(statusAtInsert).containsExactly(VehicleCommandStatus.PENDING);

        ArgumentCaptor<VehicleCommand> updateCaptor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).update(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo(VehicleCommandStatus.PUBLISHED);
        assertThat(updateCaptor.getValue().getPublishedAt()).isNotNull();
    }

    @Test
    void issueCommand_move_storesPayloadAsJsonStringForAudit() {
        service.issueCommand("SIM-F01", new VehicleCommandRequest(
                "MOVE", null, null, new VehicleCommandDestination(5.0, 6.0, 180.0, "map"), null));

        ArgumentCaptor<VehicleCommand> captor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayloadJson())
                .contains("\"destination\"").contains("\"heading\":180.0").contains("\"frameId\":\"map\"");
    }

    @Test
    void issueCommand_nonMoveCommand_storesNullPayloadJson() {
        service.issueCommand("REAL-F01", new VehicleCommandRequest("STOP", null, null, null, null));

        ArgumentCaptor<VehicleCommand> captor = ArgumentCaptor.forClass(VehicleCommand.class);
        verify(commandMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).isNull();
    }

    @Test
    void findRecentByVehicleId_limitOutOfRange_isRejected() {
        BusinessException tooSmall = catchThrowableOfType(
                () -> service.findRecentByVehicleId("REAL-F01", 0), BusinessException.class);
        BusinessException tooLarge = catchThrowableOfType(
                () -> service.findRecentByVehicleId("REAL-F01", 201), BusinessException.class);

        assertThat(tooSmall.getErrorCode()).isEqualTo(ErrorCode.COMMAND_LIMIT_INVALID);
        assertThat(tooLarge.getErrorCode()).isEqualTo(ErrorCode.COMMAND_LIMIT_INVALID);
    }

    @Test
    void findByCommandId_belongingToAnotherVehicle_isNotFound() {
        VehicleCommand other = new VehicleCommand();
        other.setCommandId("CMD-1");
        other.setVehicleId("SIM-F01");
        when(commandMapper.findByCommandId("CMD-1")).thenReturn(Optional.of(other));

        BusinessException exception = catchThrowableOfType(
                () -> service.findByCommandId("REAL-F01", "CMD-1"), BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.COMMAND_NOT_FOUND);
    }

    private VehicleCommandMessage capturePublished() {
        ArgumentCaptor<VehicleCommandMessage> captor = ArgumentCaptor.forClass(VehicleCommandMessage.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }

    private Vehicle vehicle(String vehicleId) {
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setSource(VehicleSource.REAL);
        vehicle.setActive(true);
        return vehicle;
    }
}
