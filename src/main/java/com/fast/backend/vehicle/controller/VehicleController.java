package com.fast.backend.vehicle.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.vehicle.dto.VehicleActiveUpdateRequest;
import com.fast.backend.vehicle.dto.VehicleCreateRequest;
import com.fast.backend.vehicle.dto.VehicleDetailResponse;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusCountResponse;
import com.fast.backend.vehicle.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 차량 등록·조회·집계 실제 관리 API(FR-501-1). 상태 "갱신"용 테스트 API는 별도의
 * {@link VehicleStatusTestController}로 분리했다 — 이 컨트롤러의 엔드포인트는 항상(모든 프로필에서)
 * 노출되는 정식 API이고, 상태 테스트 API는 설정값으로 켜고 끌 수 있는 임시 API이기 때문이다.
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;
    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<VehicleDetailResponse>> register(
            @Valid @RequestBody VehicleCreateRequest request) {
        VehicleDetailResponse response = vehicleService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<List<VehicleResponse>> list() {
        return ApiResponse.success(vehicleService.findActiveVehicles());
    }

    /**
     * status-counts를 {vehicleId} 경로보다 먼저 선언해야 한다 — 그렇지 않으면 "/status-counts"가
     * {vehicleId} 경로 변수로 잘못 매칭될 수 있다(Spring MVC는 선언 순서가 아니라 구체성으로 매칭하지만,
     * 가독성을 위해 더 구체적인 경로를 위에 둔다).
     */
    @GetMapping("/status-counts")
    public ApiResponse<VehicleStatusCountResponse> statusCounts() {
        return ApiResponse.success(vehicleService.countByStatus());
    }

    @GetMapping("/{vehicleId}")
    public ApiResponse<VehicleDetailResponse> detail(@PathVariable String vehicleId) {
        return ApiResponse.success(vehicleService.getDetail(vehicleId));
    }

    @PatchMapping("/{vehicleId}/active")
    public ApiResponse<VehicleDetailResponse> updateActive(
            @PathVariable String vehicleId,
            @Valid @RequestBody VehicleActiveUpdateRequest request) {
        return ApiResponse.success(vehicleService.updateActive(vehicleId, request.active()));
    }

}
