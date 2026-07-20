package com.fast.backend.mqtt.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/mqtt/test 요청 바디. Jira 이슈 검증용 임시 발행 API에서만 사용한다.
 */
public record MqttTestPublishRequest(
        @NotBlank String topic,
        @NotBlank String payload,
        @NotNull Integer qos,
        boolean retained
) {
}
