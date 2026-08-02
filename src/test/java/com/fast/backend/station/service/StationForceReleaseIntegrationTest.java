package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StationForceReleaseIntegrationTest {

    @Autowired private StationMeasurementService measurementService;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private TransportTaskMapper taskMapper;

    @Test
    void forceRelease_failsLinkedTaskAndClearsMeasurementLane() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        Cargo cargo = new Cargo();
        cargo.setCargoId("CARGO-FORCE-RELEASE");
        cargo.setCreatedAt(now);
        cargoMapper.insert(cargo);

        TransportTask task = new TransportTask();
        task.setTaskCode("TASK-FORCE-RELEASE");
        task.setCargoId(cargo.getCargoId());
        task.setStatus(TaskStatus.MOVING_TO_PICKUP);
        task.setStartedAt(now);
        task.setCreatedAt(now);
        taskMapper.insert(task);

        StationSession session = measurementService.openSession(cargo.getCargoId());
        assertThat(taskMapper.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.MEASURING);

        measurementService.forceReleaseSession(session.getSessionId());

        assertThat(taskMapper.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.FAILED);
        assertThat(taskMapper.existsMeasurementLaneBusy()).isFalse();
        assertThatThrownBy(measurementService::findActiveSession)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.STATION_SESSION_NOT_ACTIVE);
    }
}
