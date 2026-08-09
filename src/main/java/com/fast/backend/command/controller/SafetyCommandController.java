package com.fast.backend.command.controller;

import com.fast.backend.command.dto.EmergencyStopAllResponse;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.service.SafetyCommandService;
import com.fast.backend.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 차량 안전 제어 전용 REST API. 프론트가 STOP/EMERGENCY_STOP을 명확히 호출할 수
 * 있도록 전용 엔드포인트를 둔다. 단건은 기존 {@link com.fast.backend.command.service.VehicleCommandService}를
 * 재사용하고, 상태 조회는 기존 {@code GET /api/vehicles/{vehicleId}/commands[/{commandId}]}를 재사용한다
 * (중복 조회 API를 새로 만들지 않음).
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
            @PathVariable String vehicleId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.stop(vehicleId)));
    }

    @PostMapping("/{vehicleId}/commands/emergency-stop")
    public ResponseEntity<ApiResponse<VehicleCommandResponse>> emergencyStop(
            @PathVariable String vehicleId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.emergencyStop(vehicleId)));
    }

    /**
     * 정지 해제. 정지 두 종류(STOP·EMERGENCY_STOP)를 모두 푼다.
     *
     * <p>세우는 길만 있고 푸는 길이 없으면 관제 화면에서 한 번 세운 차를 다시 살릴 수 없다.
     */
    @PostMapping("/{vehicleId}/commands/resume")
    public ResponseEntity<ApiResponse<VehicleCommandResponse>> resume(
            @PathVariable String vehicleId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.resume(vehicleId)));
    }

    @PostMapping("/commands/emergency-stop-all")
    public ResponseEntity<ApiResponse<EmergencyStopAllResponse>> emergencyStopAll() {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(safetyCommandService.emergencyStopAll()));
    }
}
