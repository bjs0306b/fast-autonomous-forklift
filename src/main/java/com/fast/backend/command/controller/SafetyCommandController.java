package com.fast.backend.command.controller;

import com.fast.backend.command.dto.EmergencyStopAllResponse;
import com.fast.backend.command.dto.SafetyCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.service.SafetyCommandService;
import com.fast.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 차량 안전 제어 전용 REST API(prompt53.md 4·7·8·9장). 프론트가 STOP/EMERGENCY_STOP을 명확히 호출할 수
 * 있도록 전용 엔드포인트를 둔다. 단건은 기존 {@link com.fast.backend.command.service.VehicleCommandService}를
 * 재사용하고, 상태 조회는 기존 {@code GET /api/vehicles/{vehicleId}/commands[/{commandId}]}를 재사용한다
 * (중복 조회 API를 새로 만들지 않음).
 *
 * <p>요청 body는 선택이다({@code required=false}) — reason/requestedBy 없이도 안전 명령을 낼 수 있어야 한다.
 */
@RestController
@RequestMapping("/api/vehicles")
public class SafetyCommandController {

    private final SafetyCommandService safetyCommandService;

    public SafetyCommandController(SafetyCommandService safetyCommandService) {
        this.safetyCommandService = safetyCommandService;
    }

    @PostMapping("/{vehicleId}/commands/stop")
    public ResponseEntity<ApiResponse<VehicleCommandResponse>> stop(
            @PathVariable String vehicleId,
            @Valid @RequestBody(required = false) SafetyCommandRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.stop(vehicleId, request)));
    }

    @PostMapping("/{vehicleId}/commands/emergency-stop")
    public ResponseEntity<ApiResponse<VehicleCommandResponse>> emergencyStop(
            @PathVariable String vehicleId,
            @Valid @RequestBody(required = false) SafetyCommandRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.emergencyStop(vehicleId, request)));
    }

    @PostMapping("/commands/emergency-stop-all")
    public ResponseEntity<ApiResponse<EmergencyStopAllResponse>> emergencyStopAll(
            @Valid @RequestBody(required = false) SafetyCommandRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.emergencyStopAll(request)));
    }
}
