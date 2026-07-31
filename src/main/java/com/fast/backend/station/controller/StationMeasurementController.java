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
 * 측정 세션·측정 결과 API(prompt95.md 6장, prompt96.md).
 *
 * <p><b>수신 경로는 REST 다.</b> 옛 구조에서는 측정 결과가 MQTT
 * ({@code fast/station/{station_id}/measurement})로 들어오고 이 Controller 는 조회 전용이었다. 이제
 * 측정 데스크탑이 {@code POST /api/stations/measurements}로 직접 보내며 그 토픽 구독은 제거됐다.
 * 측정 데스크탑은 DB 에 직접 접속하지 않는다 — 이 API 가 유일한 저장 경로다.
 *
 * <p><b>정상 흐름</b>: 세션 시작 → 측정 → 결과 등록 → (조건 만족 시) 적재 추천 → 세션 종료 → 다음 세션.
 * 측정 결과가 저장되기 전에는 세션을 종료할 수 없고, 활성 세션이 있으면 새 세션을 시작할 수 없다.
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
     * 측정 데스크탑이 측정 결과를 보낸다.
     *
     * <p>요청에 {@code sessionId}는 없다 — 백엔드가 현재 활성 세션을 찾아 연결한다.
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
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(stationMeasurementService.openSession(cargoId)));
    }

    /**
     * 측정 세션을 종료한다. <b>측정 결과가 저장된 뒤에만</b> 가능하다.
     *
     * <p>측정 결과가 아직 없으면 409({@code STATION_MEASUREMENT_NOT_COMPLETED})이고 활성 세션은
     * 유지된다. 활성 세션이 아니면 409({@code STATION_SESSION_NOT_ACTIVE}) — 두 원인을 구분한다.
     * 저장된 결과의 status 는 보지 않는다(DIMENSIONS_ONLY/NO_DETECTION/UNRELIABLE 도 종료 가능).
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> closeSession(@PathVariable String sessionId) {
        stationMeasurementService.closeSession(sessionId);
        return ApiResponse.success(null);
    }

    /**
     * 운영자 강제 해제(prompt106). 측정 결과 존재 여부와 <b>무관하게</b> 설비 잠금을 푼다.
     *
     * <p>측정 데스크탑이 측정을 보내기 전에 죽으면 {@code DELETE /sessions/{id}} 는
     * {@code STATION_MEASUREMENT_NOT_COMPLETED}(409)로 거부되어 잠금이 남는다. 이 경로가 그
     * 복구 수단이며, 가짜 측정 행을 만들지 않고 {@code station_state} 만 비운다.
     *
     * <p>요청 sessionId 가 현재 점유 세션과 다르거나 이미 유휴면
     * 409({@code STATION_SESSION_NOT_ACTIVE}) — 오래된 화면에서 누른 요청이 방금 시작된 정상
     * 세션을 끊지 않게 한다. 성공 응답은 기존 {@code closeSession} 과 같은
     * {@code 200 + ApiResponse}(프로젝트 공통 규약)를 따른다.
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

    /** MQTT 시절에 저장된 행 조회용 레거시 경로. REST 로 저장된 행에는 stationId 가 없다. */
    @GetMapping("/{stationId}/measurements/latest")
    public ApiResponse<StationMeasurementResponse> getLatestByStationId(@PathVariable String stationId) {
        return ApiResponse.success(stationMeasurementService.findLatestByStationId(stationId));
    }

    @GetMapping("/measurements/{measurementId}")
    public ApiResponse<StationMeasurementResponse> getByMeasurementId(@PathVariable String measurementId) {
        return ApiResponse.success(stationMeasurementService.findByMeasurementId(measurementId));
    }
}
