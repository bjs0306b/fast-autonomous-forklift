package com.fast.backend.transport.mapper;

import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.domain.TaskFailureCode;
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
        cargo.setCreatedAt(startedAt);
        cargoMapper.insert(cargo);

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-MOVE-TIMEOUT-RACE");
        task.setCargoId(cargo.getCargoId());
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        task.setStartedAt(startedAt);
        task.setCreatedAt(startedAt);
        taskMapper.insert(task);

        assertThat(taskMapper.updateStatusIfCurrent(
                task.getId(), TaskStatus.MOVING_TO_PICKUP, TaskStatus.MEASURING,
                null, null)).isEqualTo(1);
        assertThat(taskMapper.markMeasurementRequested(
                task.getId(), LocalDateTime.of(2026, 8, 3, 12, 0),
                LocalDateTime.of(2026, 8, 3, 11, 45))).isEqualTo(1);

        assertThat(taskMapper.failMoveIfAwaitingResult(
                task.getId(), LocalDateTime.of(2026, 8, 3, 11, 55),
                LocalDateTime.of(2026, 8, 3, 12, 0),
                TaskFailureCode.MEASUREMENT_NO_RESPONSE)).isZero();
        TransportTask unchanged = taskMapper.findById(task.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(TaskStatus.MEASURING);
        assertThat(unchanged.getFailureCode()).isNull();
    }

    @Test
    void measurementLaneWait_canBeSelectedAndExpired() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 3, 11, 50);
        Cargo cargo = new Cargo();
        cargo.setCreatedAt(startedAt);
        cargoMapper.insert(cargo);

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-MEASUREMENT-LANE-WAIT");
        task.setCargoId(cargo.getCargoId());
        task.setStatus(TaskStatus.MEASURING);
        task.setStartedAt(startedAt);
        task.setCreatedAt(startedAt);
        taskMapper.insert(task);

        assertThat(taskMapper.findOldestMeasurementAwaitingRequest())
                .get()
                .extracting(TransportTask::getId)
                .isEqualTo(task.getId());
        assertThat(taskMapper.markMeasurementRequested(
                task.getId(), LocalDateTime.of(2026, 8, 3, 12, 0),
                LocalDateTime.of(2026, 8, 3, 11, 55))).isZero();
        assertThat(taskMapper.failMeasurementLaneWaitIfExpired(
                task.getId(), LocalDateTime.of(2026, 8, 3, 11, 55),
                LocalDateTime.of(2026, 8, 3, 12, 0))).isEqualTo(1);
        assertThat(taskMapper.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.FAILED);
    }

    /** 실패 원인 코드가 실제로 저장되고 다시 읽힌다. */
    @Test
    void failWithCode_persistsFailureCodeAndIsIdempotent() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 3, 11, 50);
        Cargo cargo = new Cargo();
        cargo.setCreatedAt(startedAt);
        cargoMapper.insert(cargo);

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-FAILURE-CODE");
        task.setCargoId(cargo.getCargoId());
        task.setStatus(TaskStatus.MEASURING);
        task.setStartedAt(startedAt);
        task.setCreatedAt(startedAt);
        taskMapper.insert(task);

        LocalDateTime failedAt = LocalDateTime.of(2026, 8, 3, 12, 0);
        assertThat(taskMapper.failWithCode(
                task.getId(), TaskStatus.MEASURING, failedAt,
                TaskFailureCode.PLACEMENT_INELIGIBLE)).isEqualTo(1);

        TransportTask failed = taskMapper.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo(TaskFailureCode.PLACEMENT_INELIGIBLE);

        // 두 번째 호출은 기대 상태가 더 이상 MEASURING 이 아니라 0행이다 — 중복 실패 이벤트가 나가지 않는다.
        assertThat(taskMapper.failWithCode(
                task.getId(), TaskStatus.MEASURING, failedAt,
                TaskFailureCode.MEASUREMENT_NO_RESPONSE)).isZero();
        assertThat(taskMapper.findById(task.getId()).orElseThrow().getFailureCode())
                .isEqualTo(TaskFailureCode.PLACEMENT_INELIGIBLE);
    }
}
