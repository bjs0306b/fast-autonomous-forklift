package com.fast.backend.transport.dispatch;

import com.fast.backend.command.service.VehicleCommandPublisher;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.transport.dto.TransportCommandMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실제 MQTT 발행 구현(prompt48.md 5·10·19장). 기존 {@link MqttPublisher}와 {@link MqttTopics}를 재사용해
 * 운반 명령을 기존 명령 토픽 {@code forklift/{vehicleId}/command}로 발행한다(QoS 1, retained false — 기존
 * {@link VehicleCommandPublisher} 정책 상수 재사용).
 *
 * <p><b>config-gate</b>: {@code dispatch.mqtt.enabled}가 false면 실제 발행을 하지 않고
 * {@link ErrorCode#MQTT_DISPATCH_FAILED}로 명확히 거부한다 — 기본값 false라 운영에서 실수 발행을 막는다
 * (prompt48.md 19장). 규격 미확정 TRANSPORT 명령을 임의로 실제 발행하지 않기 위한 안전장치이기도 하다(4장).
 */
@Component
public class MqttTransportCommandPublisher implements TransportCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(MqttTransportCommandPublisher.class);

    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;
    private final DispatchProperties dispatchProperties;

    public MqttTransportCommandPublisher(
            MqttPublisher mqttPublisher, MqttTopics mqttTopics, DispatchProperties dispatchProperties) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
        this.dispatchProperties = dispatchProperties;
    }

    @Override
    public void publish(TransportCommandMessage message) {
        if (!dispatchProperties.mqttEnabled()) {
            throw new BusinessException(ErrorCode.MQTT_DISPATCH_FAILED,
                    "dispatch.mqtt.enabled=false: 실제 MQTT 발행이 비활성화되어 있습니다.");
        }
        String topic = mqttTopics.vehicleCommand(message.vehicleId());
        mqttPublisher.publish(message, topic,
                VehicleCommandPublisher.COMMAND_QOS, VehicleCommandPublisher.COMMAND_RETAINED);
        log.info("Transport command published: commandId={}, vehicleId={}, topic={}",
                message.commandId(), message.vehicleId(), topic);
    }
}
