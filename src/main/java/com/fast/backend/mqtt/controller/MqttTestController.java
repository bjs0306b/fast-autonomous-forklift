package com.fast.backend.mqtt.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S15P11A304-89 검증을 위한 임시 MQTT 발행 API. 실제 지게차 제어 API가 아니다.
 * Gateway를 직접 호출하지 않고 {@link MqttPublisher}를 통해서만 발행한다.
 *
 * <p>{@code mqtt.test-api.enabled}로 켜고 끈다. 프로필({@code @Profile})이 아니라
 * 독립적인 프로퍼티 스위치를 쓰는 이유는, 이 프로젝트에 아직 {@code prod} 프로필/배포 구성이 없어
 * {@code @Profile("local")}만으로는 실질적인 운영 차단 효과가 없었기 때문이다(prompt7.md 분석 결과,
 * {@code answer7.md} 8장 참고).
 *
 * <p><b>기본값은 {@code false}(비활성화)다(prompt9.md 기준 변경).</b> 이 값을 명시적으로 설정하지 않은
 * 환경에서는 이 검증용 API가 노출되지 않는 것이 더 안전하기 때문이다. 로컬 개발 환경에서 이 API가
 * 필요하면 {@code application-local.yml}에서 {@code mqtt.test-api.enabled: true}를 명시적으로 켜야 한다
 * (이미 그렇게 설정돼 있다).
 */
@RestController
@ConditionalOnProperty(name = "mqtt.test-api.enabled", havingValue = "true", matchIfMissing = false)
public class MqttTestController {

    private final MqttPublisher mqttPublisher;

    public MqttTestController(MqttPublisher mqttPublisher) {
        this.mqttPublisher = mqttPublisher;
    }

    @PostMapping("/api/mqtt/test")
    public ApiResponse<Map<String, Object>> publishTestMessage(@Valid @RequestBody MqttTestPublishRequest request) {
        mqttPublisher.publishRaw(request.payload(), request.topic(), request.qos(), request.retained());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topic", request.topic());
        data.put("qos", request.qos());
        data.put("retained", request.retained());
        return ApiResponse.success(data);
    }
}
