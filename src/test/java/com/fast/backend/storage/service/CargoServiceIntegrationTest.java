package com.fast.backend.storage.service;

import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CargoServiceIntegrationTest {

    @Autowired private CargoService cargoService;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private TransportTaskMapper transportTaskMapper;
    @Autowired private MockMvc mockMvc;

    @Test
    void register_createsGeneratedCargoAndPendingTaskTogether() {
        var response = cargoService.register();

        assertThat(response.cargoId()).isPositive();
        assertThat(response.taskId()).startsWith("TASK-");
        assertThat(response.taskStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(cargoMapper.findByCargoId(response.cargoId())).isPresent();

        var task = transportTaskMapper.findByTaskCode(response.taskId()).orElseThrow();
        assertThat(task.getCargoId()).isEqualTo(response.cargoId());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void postCargos_withoutBody_returnsGeneratedCargoAndTask() throws Exception {
        mockMvc.perform(post("/api/cargos"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.cargoId").isNumber())
                .andExpect(jsonPath("$.data.taskId").isString())
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING"));
    }
}
