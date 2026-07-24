package com.fast.backend.command.service;

import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import org.springframework.stereotype.Component;

/**
 * 모든 차량 명령을 {@code forklift/{vehicleId}/command} 단일 토픽으로 발행한다
 * (prompt32.md 1장 7번 확정 규격).
 *
 * <p><b>이 클래스 하나가 이전의 두 Publisher를 대체한다</b> — 구
 * {@code IsaacForkliftCommandPublisher}(SIM 이동)와 구 {@code EmbeddedForkliftCommandPublisher}(실물)가
 * 같은 토픽에 서로 다른 스키마를 발행하던 구조를 통합했다.
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

    public VehicleCommandPublisher(MqttPublisher mqttPublisher, MqttTopics mqttTopics) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
    }

    public void publish(VehicleCommandMessage message) {
        if (message.vehicleId() == null || message.vehicleId().isBlank()) {
            throw new IllegalArgumentException("vehicleId must not be null or blank");
        }
        if (message.command() == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        String topic = mqttTopics.vehicleCommand(message.vehicleId());
        mqttPublisher.publish(message, topic, COMMAND_QOS, COMMAND_RETAINED);
    }
}
