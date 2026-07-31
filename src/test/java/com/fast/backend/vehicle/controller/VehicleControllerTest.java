package com.fast.backend.vehicle.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleActiveUpdateRequest;
import com.fast.backend.vehicle.dto.VehicleCreateRequest;
import com.fast.backend.vehicle.dto.VehicleDetailResponse;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusCountResponse;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryResponse;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.service.VehicleService;
import com.fast.backend.vehicle.service.VehicleStatusHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * VehicleService를 모킹해 Controller가 요청/응답을 올바른 형태로 위임·포장하는지만 검증한다
 * (이 저장소의 기존 단위 테스트 스타일 — MqttPublisherTest/MqttMessageRouterTest와 동일하게
 * MockMvc 없이 Controller를 직접 생성해서 호출한다).
 */
class VehicleControllerTest {

    private VehicleService vehicleService;
    private VehicleStatusHistoryService vehicleStatusHistoryService;
    private VehicleController controller;

    @BeforeEach
    void setUp() {
        vehicleService = mock(VehicleService.class);
        vehicleStatusHistoryService = mock(VehicleStatusHistoryService.class);
        controller = new VehicleController(vehicleService, vehicleStatusHistoryService);
    }

    @Test
    void register_returns201WithServiceResult() {
        VehicleCreateRequest request = new VehicleCreateRequest("SIM-F01", "시뮬레이션 지게차 1호");
        VehicleDetailResponse expected = new VehicleDetailResponse(
                "SIM-F01", "시뮬레이션 지게차 1호", true, null, null,
                VehicleStatusResponse.unknown());
        when(vehicleService.register(request)).thenReturn(expected);

        ResponseEntity<ApiResponse<VehicleDetailResponse>> response = controller.register(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getData()).isEqualTo(expected);
    }

    @Test
    void list_returnsServiceResultWrapped() {
        List<VehicleResponse> expected = List.of(
                new VehicleResponse("SIM-F01", "1호", true, VehicleStatusResponse.unknown()));
        when(vehicleService.findActiveVehicles()).thenReturn(expected);

        ApiResponse<List<VehicleResponse>> response = controller.list();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void detail_returnsServiceResultWrapped() {
        VehicleDetailResponse expected = new VehicleDetailResponse(
                "SIM-F01", "1호", true, null, null, VehicleStatusResponse.unknown());
        when(vehicleService.getDetail("SIM-F01")).thenReturn(expected);

        ApiResponse<VehicleDetailResponse> response = controller.detail("SIM-F01");

        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void updateActive_returnsServiceResultWrapped() {
        VehicleActiveUpdateRequest request = new VehicleActiveUpdateRequest(false);
        VehicleDetailResponse expected = new VehicleDetailResponse(
                "SIM-F01", "1호", false, null, null, VehicleStatusResponse.unknown());
        when(vehicleService.updateActive("SIM-F01", false)).thenReturn(expected);

        ApiResponse<VehicleDetailResponse> response = controller.updateActive("SIM-F01", request);

        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void statusCounts_returnsServiceResultWrapped() {
        VehicleStatusCountResponse expected = new VehicleStatusCountResponse(1,
                List.of(new VehicleStatusCountResponse.StatusCount(VehicleStatus.IDLE.name(), 1)));
        when(vehicleService.countByStatus()).thenReturn(expected);

        ApiResponse<VehicleStatusCountResponse> response = controller.statusCounts();

        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void statusHistory_defaultLimit_delegatesFiftyToService() {
        List<VehicleStatusHistoryResponse> expected = List.of(
                new VehicleStatusHistoryResponse(1L, "SIM-F01", VehicleStatus.ACTIVE, 82, 1.2, 3.4, 90.0, 0.4,
                        null, null, null, null, null, null, null, null));
        when(vehicleStatusHistoryService.findRecentHistory("SIM-F01", 50)).thenReturn(expected);

        ApiResponse<List<VehicleStatusHistoryResponse>> response = controller.statusHistory("SIM-F01", 50);

        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void statusHistory_explicitLimit_delegatesGivenLimitToService() {
        when(vehicleStatusHistoryService.findRecentHistory("SIM-F01", 10)).thenReturn(List.of());

        ApiResponse<List<VehicleStatusHistoryResponse>> response = controller.statusHistory("SIM-F01", 10);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEmpty();
    }
}
