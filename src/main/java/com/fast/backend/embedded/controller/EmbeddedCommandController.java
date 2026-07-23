package com.fast.backend.embedded.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.embedded.dto.EmbeddedCommandRequest;
import com.fast.backend.embedded.dto.EmbeddedCommandResponse;
import com.fast.backend.embedded.dto.EmbeddedErrorResponse;
import com.fast.backend.embedded.dto.EmbeddedForkStatusResponse;
import com.fast.backend.embedded.service.EmbeddedCommandService;
import com.fast.backend.embedded.service.EmbeddedErrorService;
import com.fast.backend.embedded.service.EmbeddedForkStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 실물 지게차(REAL01) 명령 발행·조회, 포크 현재 상태 조회, 오류 이력 조회 REST API(prompt29.md 18장).
 * 새 명령 결과·포크 상태·오류 수신은 REST가 아니라 MQTT로만 이뤄진다 — 이 Controller는 명령 발행과
 * 조회 전용이다("과도한 CRUD API를 만들지 않는다", 18장).
 */
@RestController
@RequestMapping("/api/vehicles/{forkliftId}")
public class EmbeddedCommandController {

    private final EmbeddedCommandService commandService;
    private final EmbeddedForkStatusService forkStatusService;
    private final EmbeddedErrorService errorService;

    public EmbeddedCommandController(EmbeddedCommandService commandService,
            EmbeddedForkStatusService forkStatusService, EmbeddedErrorService errorService) {
        this.commandService = commandService;
        this.forkStatusService = forkStatusService;
        this.errorService = errorService;
    }

    @PostMapping("/embedded-commands")
    public ResponseEntity<ApiResponse<EmbeddedCommandResponse>> issueCommand(
            @PathVariable String forkliftId, @RequestBody EmbeddedCommandRequest request) {
        EmbeddedCommandResponse response = commandService.issueCommand(forkliftId, request.command(), request.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/embedded-commands/{commandId}")
    public ApiResponse<EmbeddedCommandResponse> getCommand(
            @PathVariable String forkliftId, @PathVariable String commandId) {
        return ApiResponse.success(commandService.findByCommandId(forkliftId, commandId));
    }

    @GetMapping("/embedded-commands")
    public ApiResponse<List<EmbeddedCommandResponse>> listCommands(
            @PathVariable String forkliftId, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(commandService.findRecentByForkliftId(forkliftId, limit));
    }

    @GetMapping("/fork-status")
    public ApiResponse<EmbeddedForkStatusResponse> getForkStatus(@PathVariable String forkliftId) {
        return ApiResponse.success(forkStatusService.getCurrentForkStatus(forkliftId));
    }

    @GetMapping("/embedded-errors")
    public ApiResponse<List<EmbeddedErrorResponse>> listErrors(
            @PathVariable String forkliftId, @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(errorService.findRecentByForkliftId(forkliftId, limit));
    }
}
