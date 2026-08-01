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
 * <p>측정 프로그램은 REST로 세션을 열고 최종 결과를 등록한다. 백엔드는 같은 결과 DTO를 MQTT 수신
 * 경계에서도 받아 동일한 저장 로직으로 처리한다.
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
     * 측정 결과를 REST로 등록한다. MQTT 수신 경계도 같은 DTO와 Service를 사용한다.
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
    public ResponseEntity<ApiResponse<StationSession>> openSession(@RequestParam String cargoId) {
        StationSession session = stationMeasurementService.openSession(cargoId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(session));
    }

    /**
     * 운영자 강제 해제. 측정 결과 존재 여부와 <b>무관하게</b> 설비 잠금을 푼다.
     *
     * <p>요청 sessionId 가 현재 점유 세션과 다르거나 이미 유휴면
     * 409({@code STATION_SESSION_NOT_ACTIVE}) — 오래된 화면에서 누른 요청이 방금 시작된 정상
     * 세션을 끊지 않게 한다.
     *
     * <p>⚠️ 현재 이 저장소에는 인증·권한 체계가 없어 <b>누구나 호출할 수 있다.</b>
     * 운영 배포 전 관리자 권한으로 제한해야 한다(Service Javadoc 의 TODO 참고).
     */
    @DeleteMapping("/sessions/{sessionId}/force")
    public ApiResponse<Void> forceReleaseSession(@PathVariable String sessionId) {
        stationMeasurementService.forceReleaseSession(sessionId);
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
