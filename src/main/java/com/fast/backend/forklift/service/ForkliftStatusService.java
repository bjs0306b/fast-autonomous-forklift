package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 지게차 상태 메시지를 처리한다. 이번 단계는 MySQL/MyBatis를 사용하지 않으므로
 * 상태를 저장하지 않고 수신 사실만 로그로 남긴다.
 */
@Service
public class ForkliftStatusService {

    private static final Logger log = LoggerFactory.getLogger(ForkliftStatusService.class);

    public void handleStatus(ForkliftStatusMessage message) {
        log.info("Forklift status updated: forkliftId={}, status={}, battery={}, timestamp={}",
                message.forkliftId(), message.status(), message.battery(), message.timestamp());
    }
}
