package com.fast.backend.loadsafety.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.loadsafety.domain.LoadSafetyRiskLevel;
import com.fast.backend.loadsafety.domain.LoadSafetySource;
import com.fast.backend.loadsafety.domain.VehicleLoadSafety;
import com.fast.backend.loadsafety.dto.LoadSafetyMessage;
import com.fast.backend.loadsafety.dto.LoadSafetyResponse;
import com.fast.backend.loadsafety.mapper.VehicleLoadSafetyMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code forklift/{vehicleId}/load-safety} 메시지를 검증·저장·브로드캐스트한다(prompt63.md 3장 1·2·3번).
 *
 * <p><b>이 서비스는 위험도를 판정하지 않는다</b>(3장 "위험 기준 계산 알고리즘" 제외, "프론트는 위험도를
 * 계산하지 않는다"). 비전·센서 노드가 보낸 {@code riskLevel}/{@code riskCode}/{@code message}를 그대로
 * 보존해 중계할 뿐이며, 임계값 비교나 단계 승격/강등을 하지 않는다. 백엔드가 임의로 판정하면 화면과
 * 실제 안전 로직이 서로 다른 기준을 갖게 된다.
 *
 * <p><b>자동 정지 명령을 내리지 않는다</b>(3장 제외 범위 "지게차 자동 정지 명령", "비상정지 자동 호출").
 * DANGER를 받아도 이 서비스는 알림만 내보내며, 정지 여부는 조작자가 기존 비상정지 기능으로 결정한다.
 *
 * <p>검증 실패·미등록 차량은 {@code EmbeddedForkStatusService}와 동일하게 <b>경고 로그 후 조용히 폐기</b>
 * 한다 — MQTT 수신 경로에서 예외를 던지면 브로커 소비 스레드가 영향을 받기 때문이다.
 */
@Service
public class LoadSafetyService {

    private static final Logger log = LoggerFactory.getLogger(LoadSafetyService.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleLoadSafetyMapper loadSafetyMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public LoadSafetyService(VehicleMapper vehicleMapper, VehicleLoadSafetyMapper loadSafetyMapper,
            VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.loadSafetyMapper = loadSafetyMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void handleLoadSafety(LoadSafetyMessage message) {
        log.debug("Load safety received: vehicleId={}, riskLevel={}, riskCode={}",
                message.vehicleId(), message.riskLevel(), message.riskCode());
        try {
            if (!isValid(message)) {
                return;
            }
            // 미등록 차량 이벤트 방어(3장 9번). 임의로 차량을 만들지 않는다.
            if (!vehicleMapper.existsByVehicleId(message.vehicleId())) {
                log.warn("Load safety skipped, vehicle not registered: vehicleId={}", message.vehicleId());
                return;
            }

            LoadSafetyRiskLevel riskLevel = LoadSafetyRiskLevel.fromRaw(message.riskLevel());
            if (riskLevel == LoadSafetyRiskLevel.UNKNOWN
                    && message.riskLevel() != null && !message.riskLevel().isBlank()) {
                // 메시지를 버리지는 않는다 — 높이·기울기 측정값은 여전히 유효하다. 다만 계약 불일치를
                // 조용히 넘기면 화면에 "판정 불가"만 계속 뜨므로 반드시 경고로 드러낸다.
                log.warn("Load safety riskLevel not recognized, stored as UNKNOWN: vehicleId={}, riskLevel={}",
                        message.vehicleId(), message.riskLevel());
            }
            LoadSafetySource source = LoadSafetySource.fromRaw(message.source());

            OffsetDateTime receivedAt = CommunicationTime.nowOffset();
            VehicleLoadSafety entity = toEntity(message, riskLevel, source, receivedAt);
            loadSafetyMapper.upsert(entity);

            LoadSafetyResponse data = toResponse(entity);
            // occurredAt은 서버 처리 시각이 아니라 센서가 실제로 감지한 시각을 쓴다(RealtimeEvent 규칙).
            broadcaster.broadcastLoadSafety(message.vehicleId(), data, message.detectedAt());

            if (riskLevel.isAlerting()) {
                log.info("Load safety alert: vehicleId={}, riskLevel={}, riskCode={}, message={}",
                        message.vehicleId(), riskLevel, message.riskCode(), message.message());
            }
        } catch (RuntimeException e) {
            log.error("Load safety processing failed unexpectedly: vehicleId={}, error={}",
                    message.vehicleId(), e.getMessage());
        }
    }

    /**
     * 차량의 최신 적재 안전 상태. <b>한 번도 수신하지 못한 차량은 예외가 아니라 {@code null}</b>을 반환해
     * Controller가 {@code data:null}로 응답하게 한다 — 기존
     * {@code GET /api/vehicles/{id}/location/latest}가 위치 미수신 차량에 대해 쓰는 방식과 같다
     * (prompt50.md 9장 "새 예외 미도입"). 미수신은 오류가 아니라 정상적인 초기 상태다.
     *
     * <p>단, <b>등록되지 않은 차량</b>은 404로 거부한다 — 오타 난 vehicleId에 "데이터 없음"을 돌려주면
     * 호출자가 차량이 조용한 것인지 존재하지 않는 것인지 구분할 수 없다.
     */
    @Transactional(readOnly = true)
    public LoadSafetyResponse getLatest(String vehicleId) {
        vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));
        return loadSafetyMapper.findByVehicleId(vehicleId)
                .map(this::toResponse)
                .orElse(null);
    }

    /** 활성 차량 전체의 최신 적재 안전 상태. 미수신 차량은 결과에 포함되지 않는다(빈 행이 아니라 없음). */
    @Transactional(readOnly = true)
    public List<LoadSafetyResponse> getAllLatest() {
        return loadSafetyMapper.findAllActive().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private VehicleLoadSafety toEntity(
            LoadSafetyMessage message, LoadSafetyRiskLevel riskLevel, LoadSafetySource source,
            OffsetDateTime receivedAt) {
        LocalDateTime receivedAtLocal = CommunicationTime.toLocal(receivedAt);
        VehicleLoadSafety entity = new VehicleLoadSafety();
        entity.setVehicleId(message.vehicleId());
        entity.setCargoId(message.cargoId());
        entity.setForkHeight(message.forkHeight());
        entity.setCargoHeight(message.cargoHeight());
        entity.setRoll(message.roll());
        entity.setPitch(message.pitch());
        entity.setLoadOffsetX(message.loadOffsetX());
        entity.setLoadOffsetY(message.loadOffsetY());
        entity.setRiskLevel(riskLevel);
        entity.setRiskCode(message.riskCode());
        entity.setMessage(message.message());
        entity.setSource(source);
        entity.setDetectedAt(CommunicationTime.toLocal(message.detectedAt()));
        entity.setReceivedAt(receivedAtLocal);
        entity.setUpdatedAt(receivedAtLocal);
        return entity;
    }

    private LoadSafetyResponse toResponse(VehicleLoadSafety entity) {
        return new LoadSafetyResponse(
                entity.getVehicleId(),
                entity.getCargoId(),
                entity.getForkHeight(),
                entity.getCargoHeight(),
                entity.getRoll(),
                entity.getPitch(),
                entity.getLoadOffsetX(),
                entity.getLoadOffsetY(),
                entity.getRiskLevel() != null ? entity.getRiskLevel().name() : null,
                entity.getRiskCode(),
                entity.getMessage(),
                entity.getSource() != null ? entity.getSource().name() : null,
                CommunicationTime.toOffset(entity.getDetectedAt()),
                CommunicationTime.toOffset(entity.getReceivedAt()));
    }

    /**
     * 필수값 검증. {@code riskLevel}이 없는 메시지는 받지 않는다 — 이 기능의 존재 이유가 위험 단계 전달인데
     * 그 값이 없으면 화면에 표시할 것이 없다. 반대로 측정값(높이·기울기)은 모두 선택이다.
     */
    private boolean isValid(LoadSafetyMessage message) {
        if (message.vehicleId() == null || message.vehicleId().isBlank()) {
            log.warn("Load safety skipped: vehicleId is null or blank");
            return false;
        }
        if (message.riskLevel() == null || message.riskLevel().isBlank()) {
            log.warn("Load safety skipped: riskLevel is null or blank, vehicleId={}", message.vehicleId());
            return false;
        }
        if (message.detectedAt() == null) {
            log.warn("Load safety skipped: detectedAt is null, vehicleId={}", message.vehicleId());
            return false;
        }
        return true;
    }
}
