package com.fast.backend.transport.dto;

import java.time.OffsetDateTime;

/**
 * 차량으로 발행하는 운반 명령 MQTT payload(prompt48.md 10장). {@code commandId}는 command-result 역추적을
 * 위해 <b>반드시 포함</b>하고, taskCode도 함께 담는다.
 *
 * <p><b>TRANSPORT 명령은 ROS2와 미합의</b>다(prompt48.md 4장). 기존 {@code VehicleCommandType}에 TRANSPORT를
 * 임의로 추가하지 않고, 이 별도 envelope를 <b>config-gated 초안</b>으로 발행한다({@code dispatch.mqtt.enabled}
 * 기본 false). 규격이 확정되면 이 DTO를 확정 규격에 맞추거나 기존 명령 체계로 흡수한다.
 * 좌표 m, heading degree, forkHeight m.
 */
public record TransportCommandMessage(
        String commandId,
        String taskId,
        String vehicleId,
        String command,
        Pickup pickup,
        Destination destination,
        OffsetDateTime timestamp
) {
    public record Pickup(String palletId, Double x, Double y, Double heading) {
    }

    public record Destination(
            String slotCode, Double x, Double y, Double heading, Double forkHeight, String orientation) {
    }
}
