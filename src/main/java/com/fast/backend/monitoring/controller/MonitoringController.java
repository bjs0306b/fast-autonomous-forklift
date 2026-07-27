package com.fast.backend.monitoring.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.monitoring.dto.DashboardResponse;
import com.fast.backend.monitoring.service.MonitoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-504 대시보드 초기 조회 통합 API(prompt50.md 10장). 차량 현재 상태 + 최신 위치 + 최신 작업 상태를
 * 한 번에 반환해 대시보드 최초 진입의 다중 호출을 줄인다. 이후 갱신은 WebSocket이 담당한다.
 */
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<DashboardResponse> dashboard() {
        return ApiResponse.success(monitoringService.getDashboard());
    }
}
