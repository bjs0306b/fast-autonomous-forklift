package com.fast.backend.command.service;

import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.IsaacVehicleControlMessage;
import com.fast.backend.command.dto.IsaacVehicleTaskMessage;
import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import org.springframework.stereotype.Component;

/**
 * 모든 차량 명령을 기존 {@code forklift/{vehicleId}/command} 토픽으로 발행한다.
 * MOVE와 제어 명령은 현재 Isaac/ROS2 계약인 {@code fast/v1/vehicle/{id}/task} 및
 * {@code fast/v1/vehicle/{id}/control}에도 함께 발행한다.
 *
 * <p><b>이 클래스 하나가 이전의 두 Publisher를 대체한다</b> — 구
 * {@code IsaacForkliftCommandPublisher}(SIM 이동)와 구 {@code EmbeddedForkliftCommandPublisher}(실물)가
 * 같은 토픽에 서로 다른 스키마를 발행하던 구조를 통합했다. Isaac control 병행 발행은 구 ROS2
 * 소비자를 유지하면서 현재 시뮬레이터도 제어하기 위한 호환 경계다.
 *
 * <p><b>MQTT 정책(1장 1번·7번 확정)</b>: QoS <b>1</b>, retained <b>false</b>. 더 이상 `미확정`이 아니며
 * {@code mqtt.default-qos} 설정값을 참조하지 않고 이 클래스에서 확정값으로 고정한다 — 명령 QoS는 설정으로
 * 흔들리면 안 되는 안전 관련 계약이기 때문이다. retained=false는 재접속한 차량이 오래된 명령을 다시
 * 받아 예기치 않게 움직이는 것을 막는다.
 */
@Component
public class VehicleCommandPublisher {

    /** 확정 규격: 명령·명령 결과 QoS는 1. */
    public static final int COMMAND_QOS = 1;

    /** 확정 규격: 명령은 retained 하지 않는다. */
    public static final boolean COMMAND_RETAINED = false;

    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;
    private final VehicleIdAliasResolver vehicleIdAliasResolver;

    public VehicleCommandPublisher(
            MqttPublisher mqttPublisher,
            MqttTopics mqttTopics,
            VehicleIdAliasResolver vehicleIdAliasResolver) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
        this.vehicleIdAliasResolver = vehicleIdAliasResolver;
    }

    /**
     * REST·안전 제어·운반 작업에서 오는 차량 명령을 발행한다.
     */
    public void publish(VehicleCommandMessage message) {
        validate(message);
        publishInternal(message);
    }

    private void publishInternal(VehicleCommandMessage message) {
        String topic = mqttTopics.vehicleCommand(message.vehicleId());
        mqttPublisher.publish(message, topic, COMMAND_QOS, COMMAND_RETAINED);
        publishIsaacTask(message);
        publishIsaacControl(message);
    }

    /**
     * 기존 forklift 명령 계약과 별도로 현재 Isaac/ROS2가 구독하는 task 계약도 발행한다.
     * 기존 destination heading은 degree이므로 yaw radian으로 한 번만 변환한다.
     */
    private void publishIsaacTask(VehicleCommandMessage message) {
        if (message.command() != VehicleCommandType.MOVE) {
            return;
        }
        VehicleCommandDestination destination = message.payload() == null
                ? null : message.payload().destination();
        if (destination == null || destination.x() == null || destination.y() == null
                || destination.heading() == null) {
            throw new IllegalArgumentException("MOVE command requires a complete destination");
        }
        String externalVehicleId = externalVehicleId(message.vehicleId());
        double yaw = normalizeRadians(Math.toRadians(destination.heading()));
        mqttPublisher.publish(
                new IsaacVehicleTaskMessage(
                        message.commandId(),
                        destination.x(), destination.y(), yaw),
                mqttTopics.isaacVehicleTask(externalVehicleId), COMMAND_QOS, COMMAND_RETAINED);
    }

    /**
     * 기존 forklift 명령 계약과 별도로 현재 Isaac/ROS2가 실제 구독하는 control 계약도 발행한다.
     * 수동 STOP/비상정지를 Isaac 제어 계약으로 변환한다.
     */
    private void publishIsaacControl(VehicleCommandMessage message) {
        String isaacCommand = switch (message.command()) {
            case STOP -> "HOLD";
            case EMERGENCY_STOP -> "ESTOP";
            case RESET_ESTOP -> "RESUME";
            default -> null;
        };
        if (isaacCommand == null) {
            return;
        }
        String externalVehicleId = externalVehicleId(message.vehicleId());
        mqttPublisher.publish(
                new IsaacVehicleControlMessage(isaacCommand),
                mqttTopics.isaacVehicleControl(externalVehicleId), COMMAND_QOS, COMMAND_RETAINED);
    }

    private String externalVehicleId(String vehicleId) {
        return vehicleIdAliasResolver.resolveExternal(vehicleId)
                .orElseThrow(() -> new IllegalArgumentException("vehicleId must not be blank"));
    }

    /** Isaac 계약의 yaw 범위와 맞게 (-π, π]로 정규화한다. */
    private double normalizeRadians(double radians) {
        double normalized = Math.IEEEremainder(radians, 2 * Math.PI);
        return normalized <= -Math.PI ? normalized + 2 * Math.PI : normalized;
    }

    /** 전체 비상정지는 Isaac이 명시한 broadcast 토픽에도 즉시 한 번 발행한다. */
    public void publishIsaacGlobalEmergencyStop() {
        mqttPublisher.publish(
                new IsaacVehicleControlMessage("ESTOP"),
                mqttTopics.isaacGlobalControl(), COMMAND_QOS, COMMAND_RETAINED);
    }

    private void validate(VehicleCommandMessage message) {
        if (message.vehicleId() == null || message.vehicleId().isBlank()) {
            throw new IllegalArgumentException("vehicleId must not be null or blank");
        }
        if (message.command() == null) {
            throw new IllegalArgumentException("command must not be null");
        }
    }

}
