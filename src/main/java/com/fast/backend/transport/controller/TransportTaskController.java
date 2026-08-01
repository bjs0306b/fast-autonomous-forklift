package com.fast.backend.transport.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.dto.TransportTaskAssignRequest;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskListResponse;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.dto.TransportTaskStatusUpdateRequest;
import com.fast.backend.transport.service.TransportTaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transport-tasks")
public class TransportTaskController {

    private final TransportTaskService transportTaskService;

    public TransportTaskController(TransportTaskService transportTaskService) {
        this.transportTaskService = transportTaskService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransportTaskResponse>> create(
            @Valid @RequestBody TransportTaskCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(transportTaskService.createTask(request)));
    }

    @GetMapping
    public ApiResponse<TransportTaskListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String cargoId) {
        return ApiResponse.success(
                transportTaskService.list(page, size, parseStatus(status), vehicleId, cargoId));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<TransportTaskResponse> detail(@PathVariable String taskId) {
        return ApiResponse.success(transportTaskService.getDetail(taskId));
    }

    @PatchMapping("/{taskId}/assign")
    public ApiResponse<TransportTaskResponse> assign(
            @PathVariable String taskId, @Valid @RequestBody TransportTaskAssignRequest request) {
        return ApiResponse.success(transportTaskService.assign(taskId, request.vehicleId()));
    }

    @PatchMapping("/{taskId}/status")
    public ApiResponse<TransportTaskResponse> changeStatus(
            @PathVariable String taskId,
            @Valid @RequestBody TransportTaskStatusUpdateRequest request) {
        return ApiResponse.success(transportTaskService.changeStatus(taskId, request.status()));
    }

    private TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 status입니다: " + raw);
        }
    }
}
