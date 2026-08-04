package com.fast.backend.storage.service;

import com.fast.backend.storage.dto.CargoResponse;
import com.fast.backend.storage.event.NextCargoCreationRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;

/** 정상 측정 완료 후 일정 시간이 지나면 다음 화물과 대기 작업을 자동 생성한다. */
@Component
public class NextCargoCreationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NextCargoCreationScheduler.class);

    private final TaskScheduler taskScheduler;
    private final CargoService cargoService;
    private final Clock clock;
    private final boolean enabled;
    private final long delayMs;

    public NextCargoCreationScheduler(
            TaskScheduler taskScheduler,
            CargoService cargoService,
            Clock clock,
            @Value("${cargo.intake.auto-create-enabled:true}") boolean enabled,
            @Value("${cargo.intake.next-delay-ms:5000}") long delayMs) {
        if (delayMs < 0) {
            throw new IllegalArgumentException("cargo.intake.next-delay-ms must be zero or positive: " + delayMs);
        }
        this.taskScheduler = taskScheduler;
        this.cargoService = cargoService;
        this.clock = clock;
        this.enabled = enabled;
        this.delayMs = delayMs;
    }

    /** 측정 저장 트랜잭션이 커밋된 경우에만 다음 입하를 예약한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void schedule(NextCargoCreationRequestedEvent event) {
        if (!enabled) {
            return;
        }
        Instant scheduledAt = clock.instant().plusMillis(delayMs);
        taskScheduler.schedule(() -> createNextCargo(event), scheduledAt);
        log.info("Next cargo creation scheduled: previousCargoId={}, measurementId={}, delayMs={}",
                event.previousCargoId(), event.measurementId(), delayMs);
    }

    private void createNextCargo(NextCargoCreationRequestedEvent event) {
        try {
            CargoResponse response = cargoService.register();
            log.info("Next cargo and task created automatically: previousCargoId={}, cargoId={}, taskId={}",
                    event.previousCargoId(), response.cargoId(), response.taskId());
        } catch (RuntimeException exception) {
            // 예약 실행 스레드의 예외가 스케줄러 전체를 중단시키지 않게 격리한다.
            log.error("Automatic next cargo creation failed: previousCargoId={}, measurementId={}, error={}",
                    event.previousCargoId(), event.measurementId(), exception.getMessage(), exception);
        }
    }
}
