package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 지게차 위치 메시지를 처리한다. {@link ForkliftStatusService}와 동일하게
 * 이번 단계는 MySQL/MyBatis를 사용하지 않으므로 위치를 저장하지 않고 수신 사실만 로그로 남긴다.
 */
@Service
public class ForkliftLocationService {

    private static final Logger log = LoggerFactory.getLogger(ForkliftLocationService.class);

    public void handleLocation(ForkliftLocationMessage message) {
        log.info("Forklift location updated: forkliftId={}, x={}, y={}, direction={}, speed={}, timestamp={}",
                message.forkliftId(), message.x(), message.y(), message.direction(), message.speed(),
                message.timestamp());
    }
}
