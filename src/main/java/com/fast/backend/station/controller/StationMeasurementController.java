package com.fast.backend.station.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.service.StationMeasurementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 측정 세션과 측정 결과 API.
 *
 * <p>정상 운반 흐름에서는 MOVE 성공 후 백엔드가 cargoId로 MQTT 측정 요청을 보낸다. 측정 프로그램이
 * 세션 생성 API를 호출하면 백엔드는 해당 세션을 운반 작업에 연결하고, 측정 프로그램은 받은 sessionId로
 * 최종 결과를 REST 등록한다.
 *
 * <p><b>정상 흐름</b>: 세션 시작 → 측정 → 결과 저장과 세션 자동 해제 → 적재 추천.
 * 활성 세션이 있으면 새 세션을 시작할 수 없다.
 *
 * <p>Controller 는 요청 수신·Service 위임·응답 반환만 한다. 검증·정규화·세션 연결·안전 게이트는 모두
 * Service 에 있고, {@code BusinessException}은 여기서 잡지 않고 {@code GlobalExceptionHandler}로 넘긴다.
 */
@RestController
@RequestMapping("/api/stations")
public class StationMeasurementController {

    private final StationMeasurementService stationMeasurementService;

    public StationMeasurementController(StationMeasurementService stationMeasurementService) {
        this.stationMeasurementService = stationMeasurementService;
    }

    /**
     * 측정 결과를 REST로 등록한다.
     *
     * <p>409 가 나는 경우가 셋이고 원인이 다르다:
     * {@code STATION_SESSION_NOT_ACTIVE}(세션을 먼저 열어야 함),
     * {@code STATION_MEASUREMENT_ID_DUPLICATED}(같은 measurementId 재전송),
     * {@code STATION_SESSION_MEASUREMENT_ALREADY_EXISTS}(다른 measurementId 지만 이 세션엔 이미 결과가 있음).
     */
    @PostMapping("/measurements")
    public ResponseEntity<ApiResponse<StationMeasurementResponse>> createMeasurement(
            @RequestBody StationMeasurementCreateRequest request) {
        StationMeasurementResponse response = stationMeasurementService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    /** 측정 세션을 연다. 설비가 이미 점유 중이면 409({@code STATION_ALREADY_OCCUPIED}). */
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<StationSession>> openSession(@RequestParam Long cargoId) {
        StationSession session = stationMeasurementService.openSession(cargoId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(session));
    }

    /**
     * AI 측정 프로그램 호환 종료 API. 측정 저장 후 자동 해제된 세션은
     * {@code STATION_SESSION_NOT_ACTIVE}로 응답하며 AI는 이를 이미 종료된 정상 상태로 처리한다.
     * 측정 결과가 없는 활성 세션은 TTL이 처리하도록 종료를 거부한다.
     */
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
