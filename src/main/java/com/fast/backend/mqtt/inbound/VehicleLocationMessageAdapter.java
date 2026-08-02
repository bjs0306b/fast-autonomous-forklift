package com.fast.backend.mqtt.inbound;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.dto.LegacyIsaacLocationMessage;
import org.springframework.stereotype.Component;

/** 외부 위치 메시지를 백엔드 표준 위치 형식으로 변환한다. */
@Component
public class VehicleLocationMessageAdapter {

    private static final String DEFAULT_FRAME_ID = "map";

    public ForkliftLocationMessage fromLegacyIsaac(LegacyIsaacLocationMessage message) {
        return new ForkliftLocationMessage(
                message.forkliftId(),
                null,
                new ForkliftLocationMessage.Position(message.x(), message.y(), DEFAULT_FRAME_ID),
                radiansToDegrees(message.direction()),
                null,
                message.speed(),
                message.timestamp());
    }

    private Double radiansToDegrees(Double radians) {
        if (radians == null) {
            return null;
        }
        double degrees = Math.toDegrees(radians) % 360.0;
        return degrees < 0 ? degrees + 360.0 : degrees;
    }
}
