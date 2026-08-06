package com.fast.backend.station.service;

import com.fast.backend.station.dto.StationMeasureRequestMessage;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "station.measurement-request.dispatch-interval-ms=600000",
        "spring.datasource.url=jdbc:h2:mem:station_measurement_ready_after_commit_test;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class StationMeasurementReadyAfterCommitIntegrationTest {

    @Autowired private CargoMapper cargoMapper;
    @Autowired private TransportTaskMapper taskMapper;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;
    @MockBean private StationMeasureRequestPublisher requestPublisher;

    private Long taskId;
    private Long cargoId;

    @Test
    void readyEvent_afterCommit_persistsRequestClaimAndPublishes() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            Cargo cargo = new Cargo();
            cargo.setCreatedAt(now);
            cargoMapper.insert(cargo);
            cargoId = cargo.getCargoId();

            TransportTask task = new TransportTask();
            task.setTaskCode("TASK-AFTER-COMMIT-" + cargoId);
            task.setCargoId(cargoId);
            task.setStatus(TaskStatus.MEASURING);
            task.setStartedAt(now.minusSeconds(1));
            task.setCreatedAt(now);
            taskMapper.insert(task);
            taskId = task.getId();

            eventPublisher.publishEvent(new StationMeasurementReadyEvent(taskId));
        });

        TransportTask persisted = taskMapper.findById(taskId).orElseThrow();
        assertThat(persisted.getMeasurementRequestedAt()).isNotNull();
        verify(requestPublisher).publish(argThat((StationMeasureRequestMessage message) ->
                message.cargoId().equals(cargoId)));
    }

    @AfterEach
    void cleanup() {
        if (taskId != null) {
            jdbcTemplate.update("DELETE FROM transport_task WHERE id = ?", taskId);
        }
        if (cargoId != null) {
            jdbcTemplate.update("DELETE FROM cargo WHERE cargo_id = ?", cargoId);
        }
    }
}
