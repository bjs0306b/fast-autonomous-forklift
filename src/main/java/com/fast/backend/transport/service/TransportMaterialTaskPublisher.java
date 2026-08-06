package com.fast.backend.transport.service;

import com.fast.backend.command.service.VehicleCommandPublisher;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.transport.dto.IsaacCargoLoadMessage;
import com.fast.backend.transport.dto.Ros2MaterialTaskMessage;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 측정 결과로 확정된 목적지와 포크 높이를 DB 커밋 이후 ROS2 MQTT로 전달한다. */
@Component
public class TransportMaterialTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransportMaterialTaskPublisher.class);
    private static final double PICKUP_X = 17.0;
    private static final double PICKUP_Y = 5.0;
    private static final double PICKUP_YAW = 0.0;
    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;
    private final VehicleIdAliasResolver aliasResolver;

    public TransportMaterialTaskPublisher(
            MqttPublisher mqttPublisher,
            MqttTopics mqttTopics,
            VehicleIdAliasResolver aliasResolver) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
        this.aliasResolver = aliasResolver;
    }

    public void publishAfterCommit(
            String taskId,
            String vehicleId,
            Long cargoId,
            double cargoHeight,
            PlacementRecommendation recommendation) {
        Runnable publish = () -> send(taskId, vehicleId, cargoId, cargoHeight, recommendation);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

    private void send(
            String taskId,
            String vehicleId,
            Long cargoId,
            double cargoHeight,
            PlacementRecommendation recommendation) {
        try {
            validate(taskId, vehicleId, cargoId, cargoHeight, recommendation);
            String externalVehicleId = aliasResolver.resolveExternal(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException("vehicleId must not be blank"));
            double yaw = normalizeRadians(Math.toRadians(recommendation.destinationHeading()));
            Ros2MaterialTaskMessage message = new Ros2MaterialTaskMessage(
                    taskId,
                    new Ros2MaterialTaskMessage.Waypoint(
                            PICKUP_X, PICKUP_Y, PICKUP_YAW, 0.0),
                    new Ros2MaterialTaskMessage.Waypoint(
                            recommendation.destinationX(), recommendation.destinationY(), yaw,
                            recommendation.forkHeight()));
            mqttPublisher.publish(
                    new IsaacCargoLoadMessage(cargoHeight, String.valueOf(cargoId)),
                    mqttTopics.isaacVehicleCargo(externalVehicleId),
                    VehicleCommandPublisher.COMMAND_QOS,
                    VehicleCommandPublisher.COMMAND_RETAINED);
            mqttPublisher.publish(
                    message,
                    mqttTopics.isaacVehicleTask(externalVehicleId),
                    VehicleCommandPublisher.COMMAND_QOS,
                    VehicleCommandPublisher.COMMAND_RETAINED);
            log.info("ROS2 material task published: taskId={}, vehicleId={}, externalVehicleId={}, cargoId={}",
                    taskId, vehicleId, externalVehicleId, cargoId);
        } catch (RuntimeException exception) {
            // 측정 결과는 이미 커밋됐다. MQTT 장애 때문에 확정 데이터를 되돌릴 수 없으므로 재처리 가능한
            // taskId와 함께 오류를 남긴다.
            log.error("ROS2 material task publish failed after commit: taskId={}, vehicleId={}, error={}",
                    taskId, vehicleId, exception.getMessage(), exception);
        }
    }

    private void validate(
            String taskId,
            String vehicleId,
            Long cargoId,
            double cargoHeight,
            PlacementRecommendation recommendation) {
        if (taskId == null || taskId.isBlank() || vehicleId == null || vehicleId.isBlank()
                || cargoId == null || recommendation == null) {
            throw new IllegalArgumentException("material task identifiers and recommendation are required");
        }
        if (!Double.isFinite(cargoHeight) || cargoHeight <= 0) {
            throw new IllegalArgumentException("cargoHeight must be a positive finite meter value");
        }
        if (!Double.isFinite(recommendation.destinationX())
                || !Double.isFinite(recommendation.destinationY())
                || !Double.isFinite(recommendation.destinationHeading())
                || !Double.isFinite(recommendation.forkHeight())
                || recommendation.forkHeight() < 0) {
            throw new IllegalArgumentException("material task pose and forkHeight must be finite");
        }
    }

    private double normalizeRadians(double radians) {
        double normalized = Math.IEEEremainder(radians, 2 * Math.PI);
        return normalized <= -Math.PI ? normalized + 2 * Math.PI : normalized;
    }
}
