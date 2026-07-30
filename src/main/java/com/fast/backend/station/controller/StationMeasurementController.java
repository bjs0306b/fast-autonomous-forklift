package com.fast.backend.station.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.service.StationMeasurementService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 측정 스테이션 측정 결과 조회 API(prompt16.md 10단계). 새 측정 결과 수신은 REST가 아니라 MQTT
 * ({@code fast/station/{station_id}/measurement} 토픽, {@code StationMeasurementService#process})로만
 * 이뤄진다 — 이 Controller는 조회 전용이다(수신용 HTTP POST는 이번 범위에서 구현하지 않음, 정책 10번).
 * 목록 API는 이번 범위 밖으로 문서에 후속 항목으로 남겼다.
 */
@RestController
@RequestMapping("/api/stations")
public class StationMeasurementController {

    private final StationMeasurementService stationMeasurementService;

    public StationMeasurementController(StationMeasurementService stationMeasurementService) {
        this.stationMeasurementService = stationMeasurementService;
    }

    /**
     * 측정 세션을 연다(FR-202). 설비가 이미 점유 중이면 409.
     *
     * <p>옛 {@code GET /{stationId}/measurements/latest} 는 사라졌다 — {@code station_id} 컬럼이
     * 없어져 스테이션 기준 조회가 불가능하다. 세션 기준으로 대체한다.
     */
    @PostMapping("/sessions")
    public ApiResponse<StationSession> openSession(@RequestParam String cargoId) {
        return ApiResponse.success(stationMeasurementService.openSession(cargoId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> closeSession(@PathVariable String sessionId) {
        stationMeasurementService.closeSession(sessionId);
        return ApiResponse.success(null);
    }

    @GetMapping("/sessions/active")
    public ApiResponse<StationSession> getActiveSession() {
        return ApiResponse.success(stationMeasurementService.findActiveSession());
    }

    @GetMapping("/sessions/{sessionId}/measurements/latest")
    public ApiResponse<StationMeasurementResponse> getLatestBySessionId(@PathVariable String sessionId) {
        return ApiResponse.success(stationMeasurementService.findLatestBySessionId(sessionId));
    }

    @GetMapping("/measurements/{measurementId}")
    public ApiResponse<StationMeasurementResponse> getByMeasurementId(@PathVariable String measurementId) {
        return ApiResponse.success(stationMeasurementService.findByMeasurementId(measurementId));
    }
}
