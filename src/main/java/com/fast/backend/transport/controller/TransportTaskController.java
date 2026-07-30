package com.fast.backend.transport.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.dto.TransportTaskAssignRequest;
import com.fast.backend.transport.dto.TransportTaskCreateRequest;
import com.fast.backend.transport.dto.TransportTaskListResponse;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.dto.TransportDispatchResponse;
import com.fast.backend.transport.dto.TransportTaskStatusUpdateRequest;
import com.fast.backend.transport.dispatch.TransportDispatchService;
import com.fast.backend.transport.service.TransportTaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운반 작업 API(prompt47.md 8·9·10·11장). 기존 컨트롤러 스타일(ApiResponse 래핑, 비즈니스 로직은 Service)
 * 을 따른다. {@code {taskId}} 경로 변수는 taskCode다.
 */
@RestController
@RequestMapping("/api/transport-tasks")
public class TransportTaskController {

    private final TransportTaskService transportTaskService;
    private final TransportDispatchService transportDispatchService;

    public TransportTaskController(
            TransportTaskService transportTaskService, TransportDispatchService transportDispatchService) {
        this.transportTaskService = transportTaskService;
        this.transportDispatchService = transportDispatchService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TransportTaskResponse>> create(
            @Valid @RequestBody TransportTaskCreateRequest request) {
        TransportTaskResponse response = transportTaskService.createTask(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<TransportTaskListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String vehicleId) {
        return ApiResponse.success(
                transportTaskService.list(page, size, parseStatus(status), vehicleId));
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

    /** 수동 배정된 Task를 MQTT로 디스패치한다(prompt48.md 8장). 별도 body 없음. */
    @PostMapping("/{taskId}/dispatch")
    public ApiResponse<TransportDispatchResponse> dispatch(@PathVariable String taskId) {
        return ApiResponse.success(transportDispatchService.dispatch(taskId));
    }

    @PatchMapping("/{taskId}/status")
    public ApiResponse<TransportTaskResponse> changeStatus(
            @PathVariable String taskId, @Valid @RequestBody TransportTaskStatusUpdateRequest request) {
        return ApiResponse.success(transportTaskService.changeStatus(taskId, request.status()));
    }

    /** status 필터 문자열을 TaskStatus로 변환(빈 값이면 null, 잘못된 값이면 400). */
    private TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "알 수 없는 status 필터입니다: " + raw);
        }
    }
}
