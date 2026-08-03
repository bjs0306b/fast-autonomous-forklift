package com.fast.backend.vehicle.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryListResponse;
import com.fast.backend.vehicle.service.VehicleStatusHistoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * 차량별 상태 이력 조회 API(Jira -133). 기존 {@code VehicleController}·{@code VehicleMonitoringController}와
 * 같은 base path를 공유하되 경로가 겹치지 않는다({@code /{vehicleId}/status-history}).
 *
 * <p>{@code from}/{@code to}는 통신 규격과 동일하게 오프셋을 포함한 ISO-8601로 받는다
 * (예: {@code 2026-08-01T00:00:00+09:00}). 형식이 잘못되면 Spring이 400으로 거부한다.
 */
@RestController
@RequestMapping("/api/vehicles")
public class VehicleStatusHistoryController {

    private final VehicleStatusHistoryService vehicleStatusHistoryService;

    public VehicleStatusHistoryController(VehicleStatusHistoryService vehicleStatusHistoryService) {
        this.vehicleStatusHistoryService = vehicleStatusHistoryService;
    }

    @GetMapping("/{vehicleId}/status-history")
    public ApiResponse<VehicleStatusHistoryListResponse> statusHistory(
            @PathVariable String vehicleId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(
                vehicleStatusHistoryService.getHistory(vehicleId, from, to, status, page, size));
    }
}
