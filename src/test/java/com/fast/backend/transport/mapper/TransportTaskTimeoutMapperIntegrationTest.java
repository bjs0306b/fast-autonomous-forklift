package com.fast.backend.transport.mapper;

import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TransportTaskTimeoutMapperIntegrationTest {

    @Autowired private CargoMapper cargoMapper;
    @Autowired private TransportTaskMapper taskMapper;

    @Test
    void moveTimeout_doesNotOverwriteAJustRecordedMeasurementRequest() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 3, 11, 50);
        Cargo cargo = new Cargo();
        cargo.setCargoId("CARGO-MOVE-TIMEOUT-RACE");
        cargo.setCreatedAt(startedAt);
        cargoMapper.insert(cargo);

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-MOVE-TIMEOUT-RACE");
        task.setCargoId(cargo.getCargoId());
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        task.setStartedAt(startedAt);
        task.setCreatedAt(startedAt);
        taskMapper.insert(task);

        assertThat(taskMapper.markMeasurementRequested(
                task.getId(), LocalDateTime.of(2026, 8, 3, 12, 0))).isEqualTo(1);

        assertThat(taskMapper.failMoveIfAwaitingResult(
                task.getId(), LocalDateTime.of(2026, 8, 3, 11, 55),
                LocalDateTime.of(2026, 8, 3, 12, 0))).isZero();
        assertThat(taskMapper.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.MOVING_TO_PICKUP);
    }
}
