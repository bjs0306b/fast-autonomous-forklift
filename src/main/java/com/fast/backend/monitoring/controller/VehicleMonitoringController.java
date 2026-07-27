package com.fast.backend.monitoring.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.monitoring.dto.VehicleLocationLatestResponse;
import com.fast.backend.monitoring.dto.VehicleStatusMonitorResponse;
import com.fast.backend.monitoring.service.MonitoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FR-504 차량 현재 상태·최신 위치 조회 API(prompt50.md 8·9장). 기존 {@code VehicleController}(/api/vehicles)와
 * 같은 base path를 공유하되 모니터링 전용 엔드포인트만 담당한다(경로 충돌 없음).
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleMonitoringController {

    private final MonitoringService monitoringService;

    public VehicleMonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/status")
    public ApiResponse<List<VehicleStatusMonitorResponse>> currentStatuses() {
        return ApiResponse.success(monitoringService.getCurrentStatuses());
    }

    @GetMapping("/{vehicleId}/status")
    public ApiResponse<VehicleStatusMonitorResponse> currentStatus(@PathVariable String vehicleId) {
        return ApiResponse.success(monitoringService.getCurrentStatus(vehicleId));
    }

    @GetMapping("/locations/latest")
    public ApiResponse<List<VehicleLocationLatestResponse>> latestLocations() {
        return ApiResponse.success(monitoringService.getLatestLocations());
    }

    /** 위치 미수신 차량은 data=null로 반환한다(새 예외 미도입, prompt50.md 9장). */
    @GetMapping("/{vehicleId}/location/latest")
    public ApiResponse<VehicleLocationLatestResponse> latestLocation(@PathVariable String vehicleId) {
        return ApiResponse.success(monitoringService.getLatestLocation(vehicleId));
    }
}
