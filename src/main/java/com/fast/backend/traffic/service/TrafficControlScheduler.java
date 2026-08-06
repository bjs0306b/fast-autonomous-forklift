package com.fast.backend.traffic.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 교통 관제 판단을 설정된 주기로 실행한다 (FR-502-1a).
 *
 * <p><b>{@code matchIfMissing = false} 인 점이 다른 스케줄러와 다르다.</b> 이 프로젝트의 다른
 * 스케줄러들은 설정이 없으면 켜지지만, 이 기능은 관제가 <b>스스로 차량을 정지·재개</b>시키는
 * 경로라서 명시적으로 켠 환경에서만 돌아야 한다.
 */
@Component
@ConditionalOnProperty(prefix = "traffic", name = "enabled", havingValue = "true")
public class TrafficControlScheduler {

    private final TrafficControlService trafficControlService;

    public TrafficControlScheduler(TrafficControlService trafficControlService) {
        this.trafficControlService = trafficControlService;
    }

    @Scheduled(fixedDelayString = "${traffic.tick-ms:500}")
    public void tick() {
        trafficControlService.tick();
    }
}
