package com.fast.backend.traffic.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.traffic.domain.VehicleCycle;
import com.fast.backend.traffic.dto.ControlVehicleView;
import com.fast.backend.traffic.dto.OperationStateResponse;
import com.fast.backend.traffic.service.CycleControlService;
import com.fast.backend.traffic.service.OperationService;
import com.fast.backend.traffic.service.VehicleProcedureRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 운행 제어 API (F팀 규격 {@code backend-control-impl} §0.6).
 *
 * <p><b>왜 REST 인가.</b> MQTT 는 차량과의 통신용이고, 웹과 백엔드 사이는 REST 가 자연스럽다.
 * 프론트가 MQTT 를 직접 쏘게 하면 규칙 판정은 백엔드에 있는데 프론트가 끼어들어 서로 밀어낸다.
 *
 * <p>모든 응답이 {@link OperationStateResponse} 로 통일돼 있다 — 조작 후 화면이 다시 조회할
 * 필요 없이 바로 버튼 상태를 갱신할 수 있게 하기 위해서다.
 */
@RestController
@RequestMapping("/api/operation")
public class OperationController {

    private final OperationService operationService;
    private final CycleControlService cycleControlService;
    private final VehicleProcedureRegistry procedureRegistry;

    public OperationController(
            OperationService operationService,
            CycleControlService cycleControlService,
            VehicleProcedureRegistry procedureRegistry) {
        this.operationService = operationService;
        this.cycleControlService = cycleControlService;
        this.procedureRegistry = procedureRegistry;
    }

    /** 현재 운행 상태. 화면 진입 시 버튼 노출을 정하는 데 쓴다. */
    @GetMapping
    public ApiResponse<OperationStateResponse> current() {
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /**
     * 관제 대상 차량 목록 (규격 §0.6 응답 예).
     *
     * <p>기존 {@code GET /api/vehicles} 와 <b>따로 둔다.</b> 그쪽은 여러 화면이 쓰는 조회 API 라,
     * 관제 전용 필드({@code phase}·{@code joined}·{@code cycles})를 얹으면 관제를 쓰지 않는
     * 화면까지 그 값을 받고 "관제가 꺼져 있을 때 무엇이 맞는 값인지"를 각자 판단해야 한다.
     *
     * <p>온라인이 아닌 차량도 포함한다 — 화면은 "연결 끊김"도 보여줘야 한다.
     */
    @GetMapping("/vehicles")
    public ApiResponse<List<ControlVehicleView>> vehicles() {
        List<String> online = operationService.onlineControlledVehicles();
        List<String> controlled = operationService.controlledVehicles();

        // 관제 대상이 아닌 차량도 내려준다. 목록에서 빼 버리면 화면이 그 차량을 아예 모르게 되어
        // "왜 출발 버튼이 없지?" 를 알 길이 없다. controlled=false 로 내려보내 이유를 보여준다.
        List<ControlVehicleView> views = new ArrayList<>();
        for (String id : operationService.knownVehicles()) {
            VehicleCycle cycle = cycleControlService.cycleFor(id);
            views.add(new ControlVehicleView(
                    id,
                    OperationService.isReal(id) ? "real" : "sim",
                    online.contains(id),
                    controlled.contains(id),
                    operationService.isJoined(id),
                    operationService.isManuallyHeld(id),
                    cycle == null ? null : cycle.phase(),
                    cycle == null ? null : cycle.target(),
                    cycle == null ? 0 : cycle.cycles(),
                    procedureRegistry.isBusy(id).orElse(null),
                    procedureRegistry.step(id).orElse(null)));
        }
        return ApiResponse.success(views);
    }

    /**
     * 운행 시작.
     *
     * <p>이 호출이 곧바로 전 차량을 출발시키지는 않는다 — 다음 tick 부터 한 대씩 합류한다
     * (규칙 0). 화면은 {@code joinedCount / joinTargetCount} 로 "합류 중"을 보여주면 된다.
     */
    @PostMapping("/start")
    public ApiResponse<OperationStateResponse> start() {
        operationService.start();
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /** 전체 일시정지(HOLD). 합류 상태는 유지되므로 재개하면 이어서 돈다. */
    @PostMapping("/stop")
    public ApiResponse<OperationStateResponse> stop() {
        operationService.pause();
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /** 비상정지. 어떤 상태에서든 받아들인다. */
    @PostMapping("/estop")
    public ApiResponse<OperationStateResponse> emergencyStop() {
        operationService.emergencyStop();
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /** 재개. 개별로 세워 둔 차량까지 함께 푼다. */
    @PostMapping("/resume")
    public ApiResponse<OperationStateResponse> resume() {
        operationService.resume();
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /**
     * 차량 하나 출발 — 관제 화면에서 차량을 고르고 [출발] 을 눌렀을 때.
     *
     * <p>[운행 시작] 과 다르다. 그쪽은 규칙 0 이 3초 간격으로 전 차량을 내보내고,
     * 이쪽은 <b>고른 차 한 대만</b> 내보낸다(자동 합류를 켜지 않는다).
     *
     * <p>운행이 아직 시작 전이면 함께 {@code RUNNING} 으로 올린다 — 규격의
     * "첫 목표를 주는 것이 곧 시작"을 한 대에 적용한 것이다.
     */
    @PostMapping("/vehicles/{vehicleId}/start")
    public ApiResponse<OperationStateResponse> startVehicle(@PathVariable String vehicleId) {
        operationService.dispatchVehicle(vehicleId);
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /**
     * 차량 하나 정지 (규격 §0.6 {@code POST /api/vehicles/{id}/hold}).
     *
     * <p><b>{@code /commands/stop} 과 다르다.</b> 그쪽은 명령 한 발을 쏘고 끝이라, 다음 tick 에
     * 관제가 안전하다고 판단하면 곧바로 RESUME 을 내보내 다시 움직인다. 이쪽은 <b>"사람이
     * 세워 뒀다"를 기억</b>해서(규칙 5) 관제가 건드리지 않게 한다.
     */
    @PostMapping("/vehicles/{vehicleId}/hold")
    public ApiResponse<OperationStateResponse> holdVehicle(@PathVariable String vehicleId) {
        operationService.holdVehicle(vehicleId);
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /** 차량 하나 재개. 사람이 세워 둔 기억을 지우고 관제에 다시 맡긴다. */
    @PostMapping("/vehicles/{vehicleId}/resume")
    public ApiResponse<OperationStateResponse> resumeVehicle(@PathVariable String vehicleId) {
        operationService.resumeVehicle(vehicleId);
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }

    /** 운행 종료 — 전체 정지 + 합류 해제 + 초기화. */
    @PostMapping("/reset")
    public ApiResponse<OperationStateResponse> reset() {
        operationService.reset();
        // 주기 진행 상태도 함께 지운다 — 안 지우면 다시 시작했을 때 이전 주기의 단계가
        // 그대로 이어져, 바이에 가지도 않은 차가 랙 적재부터 시작한다.
        cycleControlService.reset();
        return ApiResponse.success(OperationStateResponse.from(operationService));
    }
}
