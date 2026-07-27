package com.fast.backend.embedded.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.embedded.dto.EmbeddedErrorResponse;
import com.fast.backend.embedded.dto.EmbeddedForkStatusResponse;
import com.fast.backend.embedded.service.EmbeddedErrorService;
import com.fast.backend.embedded.service.EmbeddedForkStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 실물 지게차 포크 현재 상태·오류 이력 조회 REST API.
 *
 * <p>이전 이름은 {@code EmbeddedCommandController}였다. 명령 발행·조회 엔드포인트가 통합 명령
 * Controller({@link com.fast.backend.command.controller.VehicleCommandController})로 이관되면서
 * 이 클래스에는 포크 상태·오류 조회만 남았고, 이름이 실제 책임과 맞도록 바꿨다(prompt32.md 3장 1번
 * "사용처를 공통 DTO로 전환한 뒤 더 이상 참조되지 않을 때만 제거").
 *
 * <p>URL 경로는 바뀌지 않았다 — {@code /api/vehicles/{forkliftId}/fork-status}와
 * {@code /api/vehicles/{forkliftId}/embedded-errors}는 그대로다.
 *
 * <p>포크 상태·오류 <b>수신</b>은 REST가 아니라 MQTT로만 이뤄진다. 이 Controller는 조회 전용이다.
 */
@RestController
@RequestMapping("/api/vehicles/{forkliftId}")
public class EmbeddedStatusController {

    private final EmbeddedForkStatusService forkStatusService;
    private final EmbeddedErrorService errorService;

    public EmbeddedStatusController(
            EmbeddedForkStatusService forkStatusService, EmbeddedErrorService errorService) {
        this.forkStatusService = forkStatusService;
        this.errorService = errorService;
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
