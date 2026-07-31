package com.fast.backend.command.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 통합 명령 REST API를 Controller → Service → Mapper → H2까지 관통해 검증한다.
 *
 * <p>테스트 프로필은 {@code mqtt.enabled=false}라 실제 MQTT 발행이 일어나지 않는다. 발행 자체가
 * 실패하므로 상태는 {@link VehicleCommandStatus#PUBLISH_FAILED}가 되는데, 이는 <b>의도된 검증 지점</b>이다 —
 * 브로커가 없을 때 백엔드가 명령을 "성공"으로 저장하지 않고 정직하게 실패로 기록하는지를 그대로 보여준다
 * (prompt32.md의 "MQTT 발행 실패 시 실행 성공으로 저장 금지").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VehicleCommandIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCommandMapper commandMapper;

    @BeforeEach
    void setUp() {
        insertVehicle("CMD-IT-01");
    }

    @Test
    void issueCommand_forkUp_persistsConfirmedCombinationAndReturns201() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"FORK_UP\",\"reason\":\"적재 준비\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vehicleId").value("CMD-IT-01"))
                .andExpect(jsonPath("$.data.command").value("FORK_UP"))
                .andExpect(jsonPath("$.data.targetSystem").value("EMBEDDED"))
                .andExpect(jsonPath("$.data.commandCategory").value("FORK"))
                .andExpect(jsonPath("$.data.commandId").isNotEmpty());
    }

    @Test
    void issueCommand_emergencyStop_persistsAllAndSafety() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"EMERGENCY_STOP\",\"reason\":\"관제 사용자 비상 정지\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetSystem").value("ALL"))
                .andExpect(jsonPath("$.data.commandCategory").value("SAFETY"));
    }

    @Test
    void issueCommand_move_persistsDestinationPayload() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"MOVE\",\"destination\":{\"x\":5.0,\"y\":6.0,"
                                + "\"heading\":180.0,\"frameId\":\"map\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.targetSystem").value("ROS2"))
                .andExpect(jsonPath("$.data.commandCategory").value("MOVE"))
                .andExpect(jsonPath("$.data.payloadJson").value(org.hamcrest.Matchers.containsString("\"frameId\":\"map\"")));
    }

    @Test
    void issueCommand_moveWithoutDestination_returns400() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"MOVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMAND_DESTINATION_INVALID"));
    }

    @Test
    void issueCommand_invalidFrameId_returns400() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"MOVE\",\"destination\":{\"x\":1.0,\"y\":2.0,\"frameId\":\"base_link\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMAND_DESTINATION_INVALID"));
    }

    @Test
    void issueCommand_wrongCombination_returns400() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"FORK_UP\",\"targetSystem\":\"ROS2\",\"commandCategory\":\"FORK\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMAND_COMBINATION_INVALID"));
    }

    @Test
    void issueCommand_unknownCommand_returns400() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"LIFT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMAND_TYPE_INVALID"));
    }

    @Test
    void issueCommand_unregisteredVehicle_returns404() throws Exception {
        mockMvc.perform(post("/api/vehicles/GHOST-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"STOP\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
    }

    @Test
    void issueCommand_withoutBroker_isStoredAsPublishFailedNotAsSuccess() throws Exception {
        String body = mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"STOP\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String commandId = objectMapper.readTree(body).get("data").get("commandId").asText();
        VehicleCommand stored = commandMapper.findByCommandId(commandId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(VehicleCommandStatus.PUBLISH_FAILED);
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getTargetSystem()).isEqualTo(VehicleCommandTargetSystem.EMBEDDED);
        assertThat(stored.getCommandCategory()).isEqualTo(VehicleCommandCategory.SAFETY);
    }

    @Test
    void listAndGetCommands_returnStoredCommands() throws Exception {
        String body = mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"UNLOAD\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String commandId = objectMapper.readTree(body).get("data").get("commandId").asText();

        mockMvc.perform(get("/api/vehicles/CMD-IT-01/commands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(get("/api/vehicles/CMD-IT-01/commands/" + commandId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.command").value("UNLOAD"))
                .andExpect(jsonPath("$.data.commandCategory").value("LOAD"));
    }

    @Test
    void listCommands_limitOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/vehicles/CMD-IT-01/commands").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMAND_LIMIT_INVALID"));
    }

    @Test
    void deprecatedEmbeddedCommandsPath_stillWorksForBackwardCompatibility() throws Exception {
        // prompt32.md 3장 5번: 이미 이 경로를 쓰는 클라이언트가 있을 수 있어 alias로 유지한다.
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/embedded-commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"FORK_DOWN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.command").value("FORK_DOWN"))
                .andExpect(jsonPath("$.data.targetSystem").value("EMBEDDED"));

        mockMvc.perform(get("/api/vehicles/CMD-IT-01/embedded-commands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void issueCommand_timestampsInResponseCarrySeoulOffset() throws Exception {
        mockMvc.perform(post("/api/vehicles/CMD-IT-01/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"command\":\"STOP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.issuedAt").value(org.hamcrest.Matchers.containsString("+09:00")));
    }

    private void insertVehicle(String vehicleId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setSource(VehicleSource.REAL);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);
    }
}
