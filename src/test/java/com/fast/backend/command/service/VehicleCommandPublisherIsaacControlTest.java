package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.IsaacVehicleControlMessage;
import com.fast.backend.command.dto.IsaacVehicleTaskMessage;
import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.dto.VehicleCommandPayload;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleCommandPublisherIsaacControlTest {

    private final MqttPublisher mqttPublisher = mock(MqttPublisher.class);
    private final MqttTopics mqttTopics = mock(MqttTopics.class);
    private final VehicleIdAliasResolver aliasResolver = mock(VehicleIdAliasResolver.class);
    private final VehicleCommandPublisher publisher = new VehicleCommandPublisher(
            mqttPublisher, mqttTopics, aliasResolver);

    @Test
    void emergencyStopIsAlsoPublishedToIsaacControlUsingExternalVehicleId() {
        OffsetDateTime issuedAt = OffsetDateTime.of(2026, 8, 6, 12, 0, 0, 0, ZoneOffset.ofHours(9));
        VehicleCommandMessage message = new VehicleCommandMessage(
                "cmd-1", "SIM-F02", VehicleCommandTargetSystem.ALL,
                VehicleCommandCategory.SAFETY, VehicleCommandType.EMERGENCY_STOP,
                VehicleCommandPayload.empty(), issuedAt);
        when(mqttTopics.vehicleCommand("SIM-F02")).thenReturn("forklift/SIM-F02/command");
        when(aliasResolver.resolveExternal("SIM-F02")).thenReturn(Optional.of("sim02"));
        when(mqttTopics.isaacVehicleControl("sim02")).thenReturn("fast/v1/vehicle/sim02/control");

        publisher.publish(message);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(mqttPublisher).publish(
                payload.capture(), org.mockito.ArgumentMatchers.eq("fast/v1/vehicle/sim02/control"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false));
        assertThat(payload.getValue()).isEqualTo(
                new IsaacVehicleControlMessage("ESTOP"));
    }

    @Test
    void normalStopMapsToIsaacHold() {
        OffsetDateTime issuedAt = OffsetDateTime.now();
        VehicleCommandMessage message = new VehicleCommandMessage(
                "cmd-2", "sim03", VehicleCommandTargetSystem.EMBEDDED,
                VehicleCommandCategory.SAFETY, VehicleCommandType.STOP,
                VehicleCommandPayload.empty(), issuedAt);
        when(mqttTopics.vehicleCommand("sim03")).thenReturn("forklift/sim03/command");
        when(aliasResolver.resolveExternal("sim03")).thenReturn(Optional.of("sim03"));
        when(mqttTopics.isaacVehicleControl("sim03")).thenReturn("fast/v1/vehicle/sim03/control");

        publisher.publish(message);

        verify(mqttPublisher).publish(
                new IsaacVehicleControlMessage("HOLD"),
                "fast/v1/vehicle/sim03/control", 1, false);
    }

    /**
     * 교통 관제(FR-502-1a)가 내는 RESUME 도 Isaac 제어 계약으로 나가야 한다.
     *
     * <p>회귀 방지: RESUME 을 VehicleCommandType 에 추가하면서 이 변환표를 갱신하지 않아,
     * STOP 은 HOLD 로 나가는데 RESUME 은 default -> null 로 빠져 <b>발행조차 되지 않았다</b>.
     * 그러면 차량이 멈춘 뒤 영영 다시 가지 않는다(2026-08-06 발견).
     */
    @Test
    void trafficResumeMapsToIsaacResume() {
        OffsetDateTime issuedAt = OffsetDateTime.now();
        VehicleCommandMessage message = new VehicleCommandMessage(
                "cmd-4", "sim03", VehicleCommandTargetSystem.EMBEDDED,
                VehicleCommandCategory.SAFETY, VehicleCommandType.RESUME,
                VehicleCommandPayload.empty(), issuedAt);
        when(mqttTopics.vehicleCommand("sim03")).thenReturn("forklift/sim03/command");
        when(aliasResolver.resolveExternal("sim03")).thenReturn(Optional.of("sim03"));
        when(mqttTopics.isaacVehicleControl("sim03")).thenReturn("fast/v1/vehicle/sim03/control");

        publisher.publish(message);

        verify(mqttPublisher).publish(
                new IsaacVehicleControlMessage("RESUME"),
                "fast/v1/vehicle/sim03/control", 1, false);
    }

    @Test
    void moveIsAlsoPublishedToIsaacTaskWithRadianYaw() {
        OffsetDateTime issuedAt = OffsetDateTime.of(2026, 8, 6, 12, 5, 0, 0, ZoneOffset.ofHours(9));
        VehicleCommandMessage message = new VehicleCommandMessage(
                "task-1", "SIM-F02", VehicleCommandTargetSystem.ROS2,
                VehicleCommandCategory.MOVE, VehicleCommandType.MOVE,
                VehicleCommandPayload.ofDestination(
                        new VehicleCommandDestination(4.9, 20.1, 270.0, "map")), issuedAt);
        when(mqttTopics.vehicleCommand("SIM-F02")).thenReturn("forklift/SIM-F02/command");
        when(aliasResolver.resolveExternal("SIM-F02")).thenReturn(Optional.of("sim02"));
        when(mqttTopics.isaacVehicleTask("sim02")).thenReturn("fast/v1/vehicle/sim02/task");

        publisher.publish(message);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(mqttPublisher).publish(
                payload.capture(), org.mockito.ArgumentMatchers.eq("fast/v1/vehicle/sim02/task"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false));
        assertThat(payload.getValue()).isEqualTo(new IsaacVehicleTaskMessage(
                "task-1", 4.9, 20.1, -Math.PI / 2));
    }

    @Test
    void globalEmergencyStopUsesIsaacBroadcastTopic() {
        when(mqttTopics.isaacGlobalControl()).thenReturn("fast/v1/control/all");

        publisher.publishIsaacGlobalEmergencyStop();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(mqttPublisher).publish(payload.capture(),
                org.mockito.ArgumentMatchers.eq("fast/v1/control/all"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false));
        assertThat(payload.getValue()).isInstanceOf(IsaacVehicleControlMessage.class);
        assertThat(((IsaacVehicleControlMessage) payload.getValue()).command()).isEqualTo("ESTOP");
    }
}
