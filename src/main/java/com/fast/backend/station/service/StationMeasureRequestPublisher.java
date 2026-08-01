package com.fast.backend.station.service;

import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.station.dto.StationMeasureRequestMessage;
import org.springframework.stereotype.Component;

/** 측정 요청을 QoS 1, retained=false로 발행한다. */
@Component
public class StationMeasureRequestPublisher {

    public static final int QOS = 1;
    public static final boolean RETAINED = false;

    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;

    public StationMeasureRequestPublisher(MqttPublisher mqttPublisher, MqttTopics mqttTopics) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
    }

    public void publish(StationMeasureRequestMessage message) {
        mqttPublisher.publish(message, mqttTopics.stationMeasureRequest(), QOS, RETAINED);
    }
}
