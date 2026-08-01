package com.fast.backend.command.controller;

import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.service.VehicleCommandService;
import com.fast.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
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

/** 차량 명령 발행과 조회 REST API. 실행 결과는 MQTT로 수신한다. */
@RestController
@RequestMapping("/api/vehicles/{vehicleId}")
public class VehicleCommandController {

    private final VehicleCommandService commandService;

    public VehicleCommandController(VehicleCommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping("/commands")
    public ResponseEntity<ApiResponse<VehicleCommandResponse>> issueCommand(
            @PathVariable String vehicleId, @Valid @RequestBody VehicleCommandRequest request) {
        VehicleCommandResponse response = commandService.issueCommand(vehicleId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/commands/{commandId}")
    public ApiResponse<VehicleCommandResponse> getCommand(
            @PathVariable String vehicleId, @PathVariable String commandId) {
        return ApiResponse.success(commandService.findByCommandId(vehicleId, commandId));
    }

    /** 차량별 최근 명령 목록. */
    @GetMapping("/commands")
    public ApiResponse<List<VehicleCommandResponse>> listCommands(
            @PathVariable String vehicleId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(commandService.findRecentByVehicleId(vehicleId, limit));
    }
}
