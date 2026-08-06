package com.fast.backend.storage.service;

import com.fast.backend.storage.dto.CargoResponse;
import com.fast.backend.storage.event.NextCargoCreationRequestedEvent;
import com.fast.backend.transport.domain.TaskStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NextCargoCreationSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    @Test
    void schedule_createsCargoAndTaskFiveSecondsLater() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        CargoService cargoService = mock(CargoService.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        NextCargoCreationScheduler scheduler =
                new NextCargoCreationScheduler(taskScheduler, cargoService, clock, true, 5000);
        when(cargoService.register()).thenReturn(new CargoResponse(
                2L, LocalDateTime.of(2026, 8, 4, 9, 0), "TASK-2", TaskStatus.PENDING));

        scheduler.schedule(new NextCargoCreationRequestedEvent(1L, "MEASUREMENT-1"));

        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Instant> scheduledAt = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(runnable.capture(), scheduledAt.capture());
        assertThat(scheduledAt.getValue()).isEqualTo(NOW.plusSeconds(5));

        runnable.getValue().run();
        verify(cargoService).register();
    }

    @Test
    void schedule_doesNothingWhenAutomaticIntakeIsDisabled() {
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        CargoService cargoService = mock(CargoService.class);
        NextCargoCreationScheduler scheduler = new NextCargoCreationScheduler(
                taskScheduler, cargoService, Clock.fixed(NOW, ZoneOffset.UTC), false, 5000);

        scheduler.schedule(new NextCargoCreationRequestedEvent(1L, "MEASUREMENT-1"));

        verifyNoInteractions(taskScheduler, cargoService);
    }

    @Test
    void constructor_rejectsNegativeDelay() {
        assertThatThrownBy(() -> new NextCargoCreationScheduler(
                mock(TaskScheduler.class), mock(CargoService.class),
                Clock.fixed(NOW, ZoneOffset.UTC), true, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("next-delay-ms");
    }
}
