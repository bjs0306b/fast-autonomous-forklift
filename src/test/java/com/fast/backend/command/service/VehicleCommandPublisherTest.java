package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.dto.VehicleCommandPayload;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 확정 MQTT 정책(prompt32.md 1장 1번·7번: 단일 command 토픽, QoS 1, retained false)을 고정한다.
 * 이 값들은 안전 관련 계약이라 설정으로 흔들리면 안 되므로 Publisher가 상수로 들고 있고, 이 테스트가
 * 그것을 명시적으로 지킨다.
 */
class VehicleCommandPublisherTest {

    private MqttPublisher mqttPublisher;
    private VehicleCommandPublisher publisher;

    @BeforeEach
    void setUp() {
        mqttPublisher = mock(MqttPublisher.class);
        MqttProperties.Topics topics = new MqttProperties.Topics(
                "forklift/+/status", "forklift/+/location", "forklift/+/path",
                "forklift/+/command-result", "forklift/+/fork-status", "forklift/+/error",
                "cargo/detected", "forklift/%s/command",
                "forklift/+/load-safety");
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null, "in", "out",
                10, 30, true, true, 1, 5000L, 5000L, topics);
        publisher = new VehicleCommandPublisher(mqttPublisher, new MqttTopics(properties));
    }

    @Test
    void publish_usesSingleCommandTopicWithQos1AndRetainedFalse() {
        publisher.publish(message("REAL-F01", VehicleCommandType.EMERGENCY_STOP,
                VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY));

        verify(mqttPublisher).publish(any(VehicleCommandMessage.class), eqTopic("forklift/REAL-F01/command"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void publish_moveAndForkCommands_shareTheSameTopic() {
        // 확정 규격: 이동·임베디드·비상정지 명령이 모두 하나의 토픽을 쓴다.
        publisher.publish(message("SIM-F01", VehicleCommandType.MOVE,
                VehicleCommandTargetSystem.ROS2, VehicleCommandCategory.MOVE));
        publisher.publish(message("SIM-F01", VehicleCommandType.FORK_UP,
                VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.FORK));

        verify(mqttPublisher, org.mockito.Mockito.times(2)).publish(
                any(VehicleCommandMessage.class), eqTopic("forklift/SIM-F01/command"),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(false));
    }

    @Test
    void publish_confirmedConstantsAreQos1AndNotRetained() {
        assertThat(VehicleCommandPublisher.COMMAND_QOS).isEqualTo(1);
        assertThat(VehicleCommandPublisher.COMMAND_RETAINED).isFalse();
    }

    @Test
    void publish_blankVehicleId_throwsAndNeverPublishes() {
        assertThatThrownBy(() -> publisher.publish(message(" ", VehicleCommandType.STOP,
                VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.SAFETY)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mqttPublisher, never()).publish(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void publish_nullCommand_throwsAndNeverPublishes() {
        VehicleCommandMessage message = new VehicleCommandMessage(
                "CMD-1", "REAL-F01", VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY,
                null, VehicleCommandPayload.empty(), null, OffsetDateTime.now(ZoneOffset.ofHours(9)));

        assertThatThrownBy(() -> publisher.publish(message)).isInstanceOf(IllegalArgumentException.class);
        verify(mqttPublisher, never()).publish(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    private String eqTopic(String topic) {
        return org.mockito.ArgumentMatchers.eq(topic);
    }

    private VehicleCommandMessage message(
            String vehicleId, VehicleCommandType command,
            VehicleCommandTargetSystem targetSystem, VehicleCommandCategory category) {
        return new VehicleCommandMessage(
                "CMD-1", vehicleId, targetSystem, category, command,
                VehicleCommandPayload.empty(), null, OffsetDateTime.now(ZoneOffset.ofHours(9)));
    }
}
