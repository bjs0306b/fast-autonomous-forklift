package com.fast.backend.transport.service;

import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.transport.dto.IsaacCargoLoadMessage;
import com.fast.backend.transport.dto.Ros2MaterialTaskMessage;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransportMaterialTaskPublisherTest {

    @Test
    void publishesMeasuredDropoffAndForkHeightToExternalVehicleTopic() {
        MqttPublisher mqttPublisher = mock(MqttPublisher.class);
        MqttTopics topics = mock(MqttTopics.class);
        VehicleIdAliasResolver aliasResolver = mock(VehicleIdAliasResolver.class);
        TransportMaterialTaskPublisher publisher =
                new TransportMaterialTaskPublisher(mqttPublisher, topics, aliasResolver);
        when(aliasResolver.resolveExternal("SIM-F02")).thenReturn(Optional.of("sim02"));
        when(topics.isaacVehicleCargo("sim02"))
                .thenReturn("fast/v1/vehicle/sim02/cargo");
        when(topics.isaacVehicleTask("sim02"))
                .thenReturn("fast/v1/vehicle/sim02/task");

        publisher.publishAfterCommit(
                "TASK-1", "SIM-F02", 12L, 0.72,
                new PlacementRecommendation("A1", 5.0, 9.4, 180.0, 1.35, 0.4, null));

        verify(mqttPublisher).publish(
                new IsaacCargoLoadMessage(0.72, "12"),
                "fast/v1/vehicle/sim02/cargo", 1, false);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(mqttPublisher).publish(
                payload.capture(),
                org.mockito.ArgumentMatchers.eq("fast/v1/vehicle/sim02/task"),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(false));
        Ros2MaterialTaskMessage message = (Ros2MaterialTaskMessage) payload.getValue();
        assertThat(message.taskId()).isEqualTo("TASK-1");
        assertThat(message.pickup().x()).isEqualTo(17.0);
        assertThat(message.pickup().y()).isEqualTo(5.0);
        assertThat(message.pickup().forkHeight()).isZero();
        assertThat(message.dropoff().x()).isEqualTo(5.0);
        assertThat(message.dropoff().y()).isEqualTo(9.4);
        assertThat(message.dropoff().yaw()).isCloseTo(Math.PI, within(1e-9));
        assertThat(message.dropoff().forkHeight()).isEqualTo(1.35);
    }
}
