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

/**
 * 차량 명령 발행·조회 REST API(prompt32.md 1장 7~11번).
 *
 * <p>이동(ROS2)·포크/적재(임베디드)·비상정지(ALL) 명령을 <b>하나의 엔드포인트</b>로 받는다 — 명령
 * 종류별로 API를 나누면 확정 규격이 통합한 envelope를 REST 계층에서 다시 쪼개는 셈이 되기 때문이다.
 *
 * <p><b>표준 경로</b>: {@code /api/vehicles/{vehicleId}/commands}
 *
 * <p><b>과도기 경로(deprecated)</b>: {@code /api/vehicles/{vehicleId}/embedded-commands}는 통합 이전의
 * 경로다. 이미 이 경로를 호출 중인 클라이언트가 있을 수 있어 <b>같은 서비스로 위임하는 alias</b>로
 * 남겨 뒀다(prompt32.md 3장 5번 하위 호환). 프론트·테스트 도구가 모두 새 경로로 전환한 것이 확인되면
 * 제거한다 — 새 기능을 이 경로에 추가하지 말 것.
 *
 * <p>새 명령 결과·포크 상태·오류 수신은 REST가 아니라 MQTT로만 이뤄진다. 이 Controller는 명령 발행과
 * 조회 전용이다.
 */
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

    /**
     * 차량별 최근 명령 목록.
     *
     * <p>{@code category}는 <b>선택</b> 필터다(prompt56.md 12장). 생략하면 기존과 동일하게 모든 분류를
     * 반환하므로 이 파라미터 추가는 기존 호출을 깨지 않는다. 관제 화면이 "최근 안전 명령"을 물을 때는
     * {@code ?limit=1&category=SAFETY}로 호출해 MOVE/FORK 명령이 섞이지 않게 한다.
     */
    @GetMapping("/commands")
    public ApiResponse<List<VehicleCommandResponse>> listCommands(
            @PathVariable String vehicleId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String category) {
        return ApiResponse.success(commandService.findRecentByVehicleId(vehicleId, limit, category));
    }

    /**
     * @deprecated 통합 이전 경로. {@code POST /api/vehicles/{vehicleId}/commands}를 사용할 것.
     *             동작은 완전히 동일하다(같은 Service 호출).
     */
    @Deprecated(forRemoval = true)
    @PostMapping("/embedded-commands")
    public ResponseEntity<ApiResponse<VehicleCommandResponse>> issueCommandLegacy(
            @PathVariable String vehicleId, @Valid @RequestBody VehicleCommandRequest request) {
        return issueCommand(vehicleId, request);
    }

    /**
     * @deprecated 통합 이전 경로. {@code GET /api/vehicles/{vehicleId}/commands/{commandId}}를 사용할 것.
     */
    @Deprecated(forRemoval = true)
    @GetMapping("/embedded-commands/{commandId}")
    public ApiResponse<VehicleCommandResponse> getCommandLegacy(
            @PathVariable String vehicleId, @PathVariable String commandId) {
        return getCommand(vehicleId, commandId);
    }

    /**
     * @deprecated 통합 이전 경로. {@code GET /api/vehicles/{vehicleId}/commands}를 사용할 것.
     */
    @Deprecated(forRemoval = true)
    @GetMapping("/embedded-commands")
    public ApiResponse<List<VehicleCommandResponse>> listCommandsLegacy(
            @PathVariable String vehicleId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String category) {
        return listCommands(vehicleId, limit, category);
    }
}
