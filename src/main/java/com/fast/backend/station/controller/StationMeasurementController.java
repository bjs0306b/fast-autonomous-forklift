package com.fast.backend.station.controller;

import com.fast.backend.common.api.ApiResponse;
<<<<<<< HEAD
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.service.StationMeasurementService;
import org.springframework.web.bind.annotation.GetMapping;
=======
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.service.StationMeasurementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 측정 스테이션 측정 결과 API(prompt16.md 10단계, prompt95.md 6장).
 *
 * <p><b>수신 경로가 REST로 바뀌었다.</b> 옛 구조에서는 측정 결과가 MQTT
 * ({@code fast/station/{station_id}/measurement})로만 들어오고 이 Controller는 조회 전용이었다. 이제
 * 측정 데스크탑이 {@code POST /api/stations/measurements}로 직접 보내며, 그 토픽 구독은 제거됐다.
 * 측정 데스크탑은 DB에 직접 접속하지 않는다 — 이 API가 유일한 저장 경로다.
 *
 * <p>Controller는 요청 수신·Service 위임·응답 반환만 한다. 검증·정규화·세션 연결은 모두 Service에 있고,
 * {@code BusinessException}은 여기서 잡지 않고 {@code GlobalExceptionHandler}로 넘긴다.
 */
@RestController
@RequestMapping("/api/stations")
public class StationMeasurementController {

    private final StationMeasurementService stationMeasurementService;

    public StationMeasurementController(StationMeasurementService stationMeasurementService) {
        this.stationMeasurementService = stationMeasurementService;
    }

<<<<<<< HEAD
    @GetMapping("/{stationId}/measurements/latest")
    public ApiResponse<StationMeasurementResponse> getLatestByStationId(@PathVariable String stationId) {
        return ApiResponse.success(stationMeasurementService.findLatestByStationId(stationId));
=======
    /**
     * 측정 데스크탑이 측정 결과를 보낸다(prompt95.md 6장).
     *
     * <p>요청에 {@code sessionId}는 없다 — 백엔드가 현재 활성 세션을 찾아 연결한다. 활성 세션이 없으면
     * 409({@code STATION_SESSION_NOT_ACTIVE})이므로 {@code POST /api/stations/sessions} 로 세션을 먼저
     * 열어야 한다. 같은 {@code measurementId}를 다시 보내면 409({@code STATION_MEASUREMENT_ID_DUPLICATED})
     * 이며 기존 값은 그대로 남는다.
     */
    @PostMapping("/measurements")
    public ResponseEntity<ApiResponse<StationMeasurementResponse>> createMeasurement(
            @RequestBody StationMeasurementCreateRequest request) {
        StationMeasurementResponse response = stationMeasurementService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
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
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
    }

    @GetMapping("/measurements/{measurementId}")
    public ApiResponse<StationMeasurementResponse> getByMeasurementId(@PathVariable String measurementId) {
        return ApiResponse.success(stationMeasurementService.findByMeasurementId(measurementId));
    }
}
