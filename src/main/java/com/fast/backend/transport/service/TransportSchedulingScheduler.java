package com.fast.backend.transport.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 대기 작업 자동 매칭을 설정된 간격으로 실행한다. */
@Component
@ConditionalOnProperty(
        prefix = "transport.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TransportSchedulingScheduler {

    private final TransportSchedulingService schedulingService;

    public TransportSchedulingScheduler(TransportSchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @Scheduled(fixedDelayString = "${transport.scheduling.interval-ms:1000}")
    public void matchNext() {
        schedulingService.matchNext();
    }
}

