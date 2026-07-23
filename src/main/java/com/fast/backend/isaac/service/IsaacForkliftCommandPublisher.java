package com.fast.backend.isaac.service;

import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.isaac.dto.IsaacForkliftCommandMessage;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 백엔드가 {@code forklift/{id}/command} 토픽으로 Isaac Sim 명령을 발행한다(prompt28.md 7장).
 *
 * <p>QoS/retained는 합의 문서에 명시되지 않았다(18장 "미확정 사항"). 이 클래스는 임시로
 * {@code mqtt.default-qos}(다른 발행 경로와 동일한 기본값)를 재사용하고 {@code retained=false}를
 * 사용한다 — 명령은 매번 새로 발행돼야 하는 일회성 지시라 재접속 시 오래된 명령이 재전달되면 안 되기
 * 때문이다. 다만 이 값이 팀 합의 없이 임의로 확정한 것임을 결과 문서에 명확히 남긴다.
 *
 * <p>{@code commandId}는 합의 JSON에 없어 추가하지 않았다(7장 9번). 명령 중복 방지·결과 추적이 필요한지는
 * 미확정 사항으로 남긴다(7장 10번).
 */
@Component
public class IsaacForkliftCommandPublisher {

    private static final String MOVE_COMMAND = "MOVE";
    private static final boolean RETAINED = false;

    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;
    private final MqttProperties mqttProperties;

    public IsaacForkliftCommandPublisher(MqttPublisher mqttPublisher, MqttTopics mqttTopics, MqttProperties mqttProperties) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
        this.mqttProperties = mqttProperties;
    }

    /**
     * MOVE 명령을 발행한다. destination 좌표·방향은 NaN/Infinity를 허용하지 않는다(7장 6번).
     */
    public void publishMove(String forkliftId, double x, double y, double direction) {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(direction, "direction");

        IsaacForkliftCommandMessage.Destination destination =
                new IsaacForkliftCommandMessage.Destination(x, y, direction);
        publish(new IsaacForkliftCommandMessage(forkliftId, MOVE_COMMAND, destination, LocalDateTime.now()));
    }

    /**
     * destination이 필요 없는 명령(예: STOP)을 발행한다.
     */
    public void publishCommand(String forkliftId, String command) {
        publish(new IsaacForkliftCommandMessage(forkliftId, command, null, LocalDateTime.now()));
    }

    private void publish(IsaacForkliftCommandMessage message) {
        if (message.forkliftId() == null || message.forkliftId().isBlank()) {
            throw new IllegalArgumentException("forkliftId must not be null or blank");
        }
        if (message.command() == null || message.command().isBlank()) {
            throw new IllegalArgumentException("command must not be null or blank");
        }
        String topic = mqttTopics.forkliftCommand(message.forkliftId());
        mqttPublisher.publish(message, topic, mqttProperties.defaultQos(), RETAINED);
    }

    private void requireFinite(double value, String fieldName) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(fieldName + " must be a finite number: " + value);
        }
    }
}
