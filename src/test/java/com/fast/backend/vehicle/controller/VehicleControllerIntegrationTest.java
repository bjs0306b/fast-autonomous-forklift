package com.fast.backend.vehicle.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleActiveUpdateRequest;
import com.fast.backend.vehicle.dto.VehicleCreateRequest;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller → Service → Mapper → 임베디드 H2까지 실제 Spring 컨텍스트로 관통하는 종단 테스트
 * (HealthControllerTest와 동일한 스타일). Mockito 단위 테스트만으로는 MyBatis XML/DDL/빈 배선이
 * 실제로 맞물리는지 증명하지 못하므로, 이 테스트가 그 마지막 연결 고리를 검증한다.
 *
 * PUT /api/vehicles/{vehicleId}/status는 여기서 다루지 않는다 — application-test.yml에서
 * vehicle.status-test-api.enabled=false(mqtt.test-api.enabled와 동일한 기본 정책)라 해당 Bean이
 * 이 컨텍스트에 존재하지 않는다. 그 경로는 VehicleStatusTestControllerTest(단위)와
 * VehicleStatusServiceTest/VehicleCurrentStatusMapperTest(단위+실DB)가 각각 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VehicleControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VehicleStatusService vehicleStatusService;

    @Autowired
    private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @Autowired
    private VehicleStatusHistoryMapper vehicleStatusHistoryMapper;

    @Test
    void register_thenListAndDetail_reflectRegisteredVehicle() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F01", "통합테스트 차량");

        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vehicleId").value("IT-F01"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.status.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.status.receivedAt").isNotEmpty());

        VehicleCurrentStatus initial = vehicleCurrentStatusMapper.findByVehicleId("IT-F01").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(initial.getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
        org.assertj.core.api.Assertions.assertThat(initial.getBattery()).isNull();
        org.assertj.core.api.Assertions.assertThat(initial.getPositionX()).isNull();
        org.assertj.core.api.Assertions.assertThat(initial.getPositionY()).isNull();
        org.assertj.core.api.Assertions.assertThat(initial.getHeading()).isNull();
        org.assertj.core.api.Assertions.assertThat(initial.getSpeed()).isNull();
        org.assertj.core.api.Assertions.assertThat(initial.getMessageAt()).isNull();
        org.assertj.core.api.Assertions.assertThat(initial.getReceivedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(initial.getUpdatedAt()).isNotNull();

        mockMvc.perform(get("/api/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.vehicleId == 'IT-F01')]").exists());

        mockMvc.perform(get("/api/vehicles/IT-F01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value("IT-F01"));
    }

    @Test
    void register_duplicateVehicleId_returns409() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F02", "중복 테스트");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VEHICLE_ID_DUPLICATED"));

        org.assertj.core.api.Assertions.assertThat(
                vehicleCurrentStatusMapper.findAllByVehicleIds(java.util.List.of("IT-F02"))).hasSize(1);
    }

    @Test
    void register_thenNewerStatus_upsertsInitialRowAndAppendsHistory() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F10", "초기 상태 갱신 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        vehicleStatusService.updateCurrentStatus("IT-F10",
                new VehicleStatusUpdateCommand("ACTIVE", 91, 1.0, 2.0, 45.0, 0.5,
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)).withNano(0)));

        VehicleCurrentStatus current = vehicleCurrentStatusMapper.findByVehicleId("IT-F10").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(current.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        org.assertj.core.api.Assertions.assertThat(current.getBattery()).isEqualTo(91);
        org.assertj.core.api.Assertions.assertThat(
                vehicleStatusHistoryMapper.findRecentByVehicleId("IT-F10", 10)).hasSize(1);
    }

    @Test
    void updateActive_falseThenTrue_filtersAndPreservesStatusAndHistory() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F11", "활성 변경 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        vehicleStatusService.updateCurrentStatus("IT-F11",
                new VehicleStatusUpdateCommand("ACTIVE", 80, null, null, null, null,
                        java.time.OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)).withNano(0)));

        mockMvc.perform(patch("/api/vehicles/IT-F11/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VehicleActiveUpdateRequest(false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value("IT-F11"))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.status.status").value("ACTIVE"));

        mockMvc.perform(get("/api/vehicles"))
                .andExpect(jsonPath("$.data[?(@.vehicleId == 'IT-F11')]").doesNotExist());
        mockMvc.perform(get("/api/vehicles/status-counts"))
                .andExpect(jsonPath("$.data.total").value(0));
        org.assertj.core.api.Assertions.assertThat(vehicleCurrentStatusMapper.findByVehicleId("IT-F11")).isPresent();
        org.assertj.core.api.Assertions.assertThat(
                vehicleStatusHistoryMapper.findRecentByVehicleId("IT-F11", 10)).hasSize(1);

        mockMvc.perform(patch("/api/vehicles/IT-F11/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new VehicleActiveUpdateRequest(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));
        mockMvc.perform(get("/api/vehicles"))
                .andExpect(jsonPath("$.data[?(@.vehicleId == 'IT-F11')]").exists());
        mockMvc.perform(get("/api/vehicles/status-counts"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[?(@.status == 'ACTIVE')].count").value(1));
    }

    @Test
    void updateActive_unknownVehicle_returns404() throws Exception {
        mockMvc.perform(patch("/api/vehicles/NO-SUCH-VEHICLE/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
    }

    @Test
    void updateActive_missingOrNullValue_returns400() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F12", "active 검증 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/vehicles/IT-F12/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mockMvc.perform(patch("/api/vehicles/IT-F12/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void updateActive_wrongJsonType_returns400() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F13", "active 타입 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/vehicles/IT-F13/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":\"not-boolean\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("JSON_PARSE_ERROR"));
    }

    @Test
    void statusCounts_multipleVehicles_excludesInactiveAndKeepsEachActiveStatus() throws Exception {
        for (VehicleCreateRequest request : java.util.List.of(
                new VehicleCreateRequest("IT-F14", "집계 ACTIVE 차량"),
                new VehicleCreateRequest("IT-F15", "집계 IDLE 차량"),
                new VehicleCreateRequest("IT-F16", "집계 UNKNOWN 차량"))) {
            mockMvc.perform(post("/api/vehicles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
        java.time.LocalDateTime messageAt = java.time.LocalDateTime.now().withNano(0);
        vehicleStatusService.updateCurrentStatus("IT-F14",
                new VehicleStatusUpdateCommand("ACTIVE", 80, null, null, null, null, messageAt.atOffset(java.time.ZoneOffset.ofHours(9))));
        vehicleStatusService.updateCurrentStatus("IT-F15",
                new VehicleStatusUpdateCommand("IDLE", 70, null, null, null, null, messageAt.atOffset(java.time.ZoneOffset.ofHours(9))));
        mockMvc.perform(patch("/api/vehicles/IT-F15/active")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/vehicles/status-counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[?(@.status == 'ACTIVE')].count").value(1))
                .andExpect(jsonPath("$.data.items[?(@.status == 'UNKNOWN')].count").value(1))
                .andExpect(jsonPath("$.data.items[?(@.status == 'IDLE')].count").value(0));
    }

    @Test
    void register_invalidSourceValue_returns400() throws Exception {
        String invalidJson = "{\"vehicleId\":\"IT-F03\",\"name\":\"잘못된 source\",\"source\":\"NOT_A_SOURCE\"}";

        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("JSON_PARSE_ERROR"));
    }

    @Test
    void register_missingSource_returns400() throws Exception {
        String missingSourceJson = "{\"vehicleId\":\"IT-F04\",\"name\":\"source 누락\"}";

        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingSourceJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void detail_unregisteredVehicleId_returns404() throws Exception {
        mockMvc.perform(get("/api/vehicles/NO-SUCH-VEHICLE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
    }

    @Test
    void statusCounts_returnsAllTenStatusesWithTotal() throws Exception {
        mockMvc.perform(get("/api/vehicles/status-counts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(10));
    }

    /**
     * 차량 등록 → 상태 갱신 2회 → vehicle_current_status는 최신값 1건 → vehicle_status_history는 2건 →
     * 조회 API는 최신순 2건 반환(prompt22.md 8장 "통합 테스트"). PUT /api/vehicles/{id}/status 테스트
     * API는 이 프로필에서 비활성화돼 있으므로(클래스 상단 Javadoc 참고), 상태 갱신은
     * VehicleStatusService를 직접 호출해 MQTT/REST 어느 경로든 공유하는 그 진입점 하나만으로도 이력이
     * 쌓이는지 검증한다.
     */
    @Test
    void statusHistory_afterTwoStatusUpdates_returnsTwoEntriesNewestFirst() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F05", "이력 테스트 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        java.time.LocalDateTime t1 = java.time.LocalDateTime.now().minusMinutes(1);
        java.time.LocalDateTime t2 = java.time.LocalDateTime.now();
        vehicleStatusService.updateCurrentStatus("IT-F05",
                new VehicleStatusUpdateCommand("IDLE", 70, null, null, null, null, t1.atOffset(java.time.ZoneOffset.ofHours(9))));
        vehicleStatusService.updateCurrentStatus("IT-F05",
                new VehicleStatusUpdateCommand("ACTIVE", 65, 1.0, 2.0, 90.0, 0.5, t2.atOffset(java.time.ZoneOffset.ofHours(9))));

        mockMvc.perform(get("/api/vehicles/IT-F05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.status.battery").value(65));

        mockMvc.perform(get("/api/vehicles/IT-F05/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[1].status").value("IDLE"));
    }

    @Test
    void statusHistory_unregisteredVehicle_returns404() throws Exception {
        mockMvc.perform(get("/api/vehicles/NO-SUCH-VEHICLE/status-history"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
    }

    @Test
    void statusHistory_noHistoryYet_returnsEmptyArray() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F06", "이력 없는 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/IT-F06/status-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void statusHistory_limitZero_returns400() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F07", "limit 검증 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/IT-F07/status-history?limit=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VEHICLE_STATUS_HISTORY_LIMIT_INVALID"));
    }

    @Test
    void statusHistory_limitAboveMaximum_returns400() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F08", "limit 상한 검증 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/IT-F08/status-history?limit=201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VEHICLE_STATUS_HISTORY_LIMIT_INVALID"));
    }

    @Test
    void statusHistory_nonNumericLimit_returns400() throws Exception {
        VehicleCreateRequest request = new VehicleCreateRequest("IT-F09", "limit 형식 검증 차량");
        mockMvc.perform(post("/api/vehicles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/vehicles/IT-F09/status-history?limit=abc"))
                .andExpect(status().isBadRequest());
    }
}
