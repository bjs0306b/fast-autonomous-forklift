package com.fast.backend.forklift.dto;

import java.time.OffsetDateTime;

/**
 * 기존 Isaac Sim {@code twin_bridge.py}가 발행하는 평면 위치 메시지다.
 *
 * <p>이 형식은 MQTT 수신 경계에서만 허용하며, 내부 서비스에는
 * {@link ForkliftLocationMessage}로 변환해 전달한다.
 */
public record LegacyIsaacLocationMessage(
        String forkliftId,
        Double x,
        Double y,
        Double direction,
        Double speed,
        OffsetDateTime timestamp
) {
}
