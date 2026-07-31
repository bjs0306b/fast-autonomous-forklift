package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementBox;
import com.fast.backend.station.domain.StationMeasurementStatus;
<<<<<<< HEAD
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.mapper.StationMeasurementBoxMapper;
=======
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.TippingLevel;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationMeasurementResponseMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 측정 스테이션 측정 결과의 수신·검증·저장·조회·브로드캐스트를 담당한다(FR-101-5, FR-202).
 *
 * <p><b>수신 경로가 MQTT에서 REST로 바뀌었다(prompt95.md).</b> 옛 {@code process(StationMeasurementMessage)}는
 * {@code fast/station/{station_id}/measurement} 토픽에서 호출되며 검증 실패를 <b>내부에서 삼키고 로그만
 * 남겼다</b> — MQTT 발행자에게는 응답할 방법이 없어 메시지 하나를 폐기하는 것이 최선이었기 때문이다.
 * REST는 반대다: 호출자(측정 데스크탑)가 결과를 알아야 재전송·재측정을 판단할 수 있으므로
 * {@link #create}는 {@link BusinessException}을 <b>삼키지 않고</b> 그대로 던져
 * {@code GlobalExceptionHandler}가 HTTP 상태로 옮기게 한다.
 *
 * <p><b>백엔드는 판정하지 않는다.</b> 전복 등급·돌출률은 측정 데스크탑이 계산해 보낸 값을 검증·정규화해
 * 저장만 한다. 값이 없는 status(치수만·미검출·측정실패)에서 임의의 기본값을 채워 넣지 않는다.
 */
@Service
public class StationMeasurementService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementService.class);

    private static final int MAX_MEASUREMENT_ID_LENGTH = 100;

    private final StationMeasurementMapper measurementMapper;
<<<<<<< HEAD
    private final StationMeasurementBoxMapper boxMapper;
    private final StationMeasurementAdapter adapter;
    private final StationMeasurementBroadcaster broadcaster;

    public StationMeasurementService(StationMeasurementMapper measurementMapper,
            StationMeasurementBoxMapper boxMapper, StationMeasurementAdapter adapter,
            StationMeasurementBroadcaster broadcaster) {
        this.measurementMapper = measurementMapper;
        this.boxMapper = boxMapper;
        this.adapter = adapter;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void process(StationMeasurementMessage message) {
        try {
            StationMeasurementStatus status = validate(message);

            if (measurementMapper.existsByMeasurementId(message.measurementId())) {
                // 중복 measurement_id: 기존 저장값을 덮어쓰지 않고 무시(경고 로그 후 정상 종료).
                log.warn("Duplicate station measurement ignored: measurementId={}", message.measurementId());
                return;
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            List<StationDirection> directions = parseDirections(message.loadBalance());
            StationMeasurement entity = adapter.toEntity(message, status, directions, receivedAt);
            measurementMapper.insert(entity);

            List<StationMeasurementBox> boxes = adapter.toBoxes(message, receivedAt);
            for (StationMeasurementBox box : boxes) {
                box.setStationMeasurementId(entity.getId());
                boxMapper.insert(box);
            }

            log.info("Station measurement stored: measurementId={}, stationId={}, status={}, boxes={}",
                    entity.getMeasurementId(), entity.getStationId(), status, boxes.size());

            StationMeasurementResponse response = adapter.toResponse(entity, boxes);
            broadcaster.broadcast(response, receivedAt);
        } catch (BusinessException e) {
            log.warn("Station measurement message rejected: measurementId={}, errorCode={}, message={}",
                    message.measurementId(), e.getErrorCode(), e.getMessage());
=======
    private final StationSessionMapper sessionMapper;
    private final StationMeasurementResponseMapper responseMapper;
    private final StationMeasurementBroadcaster broadcaster;

    public StationMeasurementService(StationMeasurementMapper measurementMapper,
            StationSessionMapper sessionMapper, StationMeasurementResponseMapper responseMapper,
            StationMeasurementBroadcaster broadcaster) {
        this.measurementMapper = measurementMapper;
        this.sessionMapper = sessionMapper;
        this.responseMapper = responseMapper;
        this.broadcaster = broadcaster;
    }

    // ── 세션 ────────────────────────────────────────────────────────────────

    /**
     * 화물 하나에 대한 측정 세션을 연다(FR-202 신규, prompt85).
     *
     * <p>설비는 하나뿐이라 동시에 한 세션만 점유할 수 있다. 점유는 조건부 UPDATE 한 문장으로 하며,
     * 두 요청이 동시에 와도 하나만 이긴다(진 쪽은 409). SELECT 로 먼저 확인하고 UPDATE 하면
     * 둘 다 빈 상태를 보고 동시에 점유할 수 있다.
     */
    @Transactional
    public StationSession openSession(String cargoId) {
        String sessionId = java.util.UUID.randomUUID().toString();
        sessionMapper.insert(new StationSession(sessionId, cargoId));
        if (sessionMapper.acquireStation(sessionId) == 0) {
            throw new BusinessException(ErrorCode.STATION_ALREADY_OCCUPIED,
                    "측정 설비가 이미 점유 중입니다. 기존 세션을 먼저 종료하세요.");
        }
        log.info("Station session opened: sessionId={}, cargoId={}", sessionId, cargoId);
        return new StationSession(sessionId, cargoId);
    }

    /** 세션 점유를 해제한다. 세션 행 자체는 남긴다 — 측정 결과가 FK 로 참조하기 때문이다. */
    @Transactional
    public void closeSession(String sessionId) {
        if (sessionMapper.releaseStation(sessionId) == 0) {
            throw new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                    "활성 세션이 아닙니다: " + sessionId);
        }
        log.info("Station session closed: sessionId={}", sessionId);
    }

    @Transactional(readOnly = true)
    public StationSession findActiveSession() {
        return sessionMapper.findActiveSession()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                        "활성화된 측정 세션이 없습니다."));
    }

    // ── REST 저장 ───────────────────────────────────────────────────────────

    /**
     * 측정 데스크탑이 보낸 측정 결과를 저장한다({@code POST /api/stations/measurements}).
     *
     * <p>순서에 의미가 있다 — 중복 확인을 활성 세션 조회보다 <b>먼저</b> 한다. 재전송된 요청이
     * "세션이 없다(409 SESSION_NOT_ACTIVE)"로 보고되면 데스크탑이 원인을 오해하기 때문이다.
     *
     * <p>{@code sessionId}는 요청에서 받지 않고 현재 활성 세션에서 가져온다(prompt95.md 7장).
     * 활성 세션이 없으면 어느 화물의 측정인지 알 수 없으므로 저장하지 않는다 — 세션을 새로 만들어
     * 붙이지 않는다.
     */
    @Transactional
    public StationMeasurementResponse create(StationMeasurementCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID, "요청 본문이 비어 있습니다.");
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
        }
        String measurementId = validateMeasurementId(request.measurementId());
        StationMeasurementStatus status = StationMeasurementStatus.fromRaw(request.status())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                        "알 수 없는 status 값입니다: " + request.status()));
        TippingLevel tippingLevel = validateByStatus(request, status);

        if (measurementMapper.existsByMeasurementId(measurementId)) {
            // REST 는 호출자에게 결과를 알려야 한다 — MQTT 시절처럼 조용히 무시하지 않는다.
            // 기존 행을 갱신하지도, 새 행을 만들지도, 재브로드캐스트하지도 않는다.
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_ID_DUPLICATED,
                    "이미 저장된 measurement_id 입니다: " + measurementId);
        }

        StationSession session = sessionMapper.findActiveSession()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                        "활성 세션이 없어 측정 결과를 저장할 수 없습니다: measurementId=" + measurementId));

        LocalDateTime receivedAt = LocalDateTime.now();
        StationMeasurement entity = new StationMeasurement();
        entity.setMeasurementId(measurementId);
        entity.setSessionId(session.getSessionId());
        entity.setStatus(status);
        entity.setCargoHeight(request.cargoHeight());
        // 소문자 입력을 대문자로 정규화해 저장한다 — DB CHECK 가 대문자만 허용한다.
        entity.setTippingLevel(tippingLevel == null ? null : tippingLevel.name());
        entity.setOverhangRatio(request.overhangRatio());
        entity.setCreatedAt(receivedAt);
        measurementMapper.insert(entity);

        log.info("Station measurement stored: measurementId={}, sessionId={}, cargoId={}, status={}, "
                        + "cargoHeightM={}, tippingLevel={}, overhangRatio={}",
                entity.getMeasurementId(), session.getSessionId(), session.getCargoId(), status,
                entity.getCargoHeight(), entity.getTippingLevel(), entity.getOverhangRatio());

        StationMeasurementResponse response = responseMapper.toResponse(entity, session.getCargoId());
        broadcaster.broadcast(response, receivedAt);
        return response;
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public StationMeasurementResponse findByMeasurementId(String measurementId) {
        StationMeasurement m = measurementMapper.findByMeasurementId(measurementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "존재하지 않는 측정 결과입니다: " + measurementId));
<<<<<<< HEAD
        return adapter.toResponse(m, boxMapper.findByStationMeasurementId(m.getId()));
=======
        return responseMapper.toResponse(m, cargoIdOf(m.getSessionId()));
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
    }

    @Transactional(readOnly = true)
    public StationMeasurementResponse findLatestByStationId(String stationId) {
        StationMeasurement m = measurementMapper.findLatestByStationId(stationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
<<<<<<< HEAD
                        "스테이션의 측정 결과가 없습니다: " + stationId));
        return adapter.toResponse(m, boxMapper.findByStationMeasurementId(m.getId()));
=======
                        "세션의 측정 결과가 없습니다: " + sessionId));
        return responseMapper.toResponse(m, cargoIdOf(sessionId));
    }

    private String cargoIdOf(String sessionId) {
        return sessionMapper.findBySessionId(sessionId)
                .map(StationSession::getCargoId)
                .orElse(null);
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    private String validateMeasurementId(String measurementId) {
        if (measurementId == null || measurementId.isBlank()) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID, "measurementId 는 필수입니다.");
        }
        if (measurementId.length() > MAX_MEASUREMENT_ID_LENGTH) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID,
                    "measurementId 는 " + MAX_MEASUREMENT_ID_LENGTH + "자 이하여야 합니다: " + measurementId.length());
        }
        return measurementId;
    }

    /**
     * status 별 필드 조합을 검증하고 정규화된 전복 등급을 돌려준다(prompt95.md 15장).
     *
     * <p>값이 있으면 안 되는 자리에 값이 오면 <b>조용히 무시하지 않고 거부한다</b> — 데스크탑이 잘못된
     * 판정을 보내고 있는데 백엔드가 삼키면 아무도 눈치채지 못한다.
     */
    private TippingLevel validateByStatus(StationMeasurementCreateRequest request,
            StationMeasurementStatus status) {
        return switch (status) {
            case OK -> {
                requireCargoHeight(request.cargoHeight(), status);
                requireOverhangRatio(request.overhangRatio());
                yield requireTippingLevel(request.tippingLevel());
            }
            case DIMENSIONS_ONLY -> {
                // 치수 측정은 성공했지만 파렛트를 못 찾아 전복·돌출 판정이 불가능한 상태다.
                requireCargoHeight(request.cargoHeight(), status);
                requireAbsent(request.tippingLevel(), "tippingLevel", status);
                requireAbsent(request.overhangRatio(), "overhangRatio", status);
                yield null;
            }
            case NO_DETECTION, UNRELIABLE -> {
                requireAbsent(request.cargoHeight(), "cargoHeight", status);
                requireAbsent(request.tippingLevel(), "tippingLevel", status);
                requireAbsent(request.overhangRatio(), "overhangRatio", status);
                yield null;
            }
        };
    }

    /**
     * 화물 높이는 <b>meter</b> 단위의 화물만의 높이다. cm 로 들어온 값(예: 72.3)을 자동으로 나누지
     * 않는다 — 단위를 추측하면 10배 틀린 값이 조용히 저장된다.
     */
    private void requireCargoHeight(Double cargoHeight, StationMeasurementStatus status) {
        if (cargoHeight == null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID,
                    "status=" + status.rawValue() + " 이면 cargoHeight 는 필수입니다.");
        }
        if (!Double.isFinite(cargoHeight) || cargoHeight <= 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID,
                    "cargoHeight 는 0보다 큰 유한값(meter)이어야 합니다: " + cargoHeight);
        }
    }

    private TippingLevel requireTippingLevel(String rawTippingLevel) {
        return TippingLevel.fromRaw(rawTippingLevel)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID,
                        "tippingLevel 은 safe/warning/danger 중 하나여야 합니다: " + rawTippingLevel));
    }

    /** 상한은 두지 않는다 — 화물이 파렛트보다 넓으면 1.0 을 넘을 수 있고, 문서상 상한이 없다. */
    private void requireOverhangRatio(Double overhangRatio) {
        if (overhangRatio == null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID,
                    "status=ok 이면 overhangRatio 는 필수입니다.");
        }
        if (!Double.isFinite(overhangRatio) || overhangRatio < 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID,
                    "overhangRatio 는 0 이상의 유한값이어야 합니다: " + overhangRatio);
        }
    }

    private void requireAbsent(Object value, String fieldName, StationMeasurementStatus status) {
        if (value != null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                    "status=" + status.rawValue() + " 이면 " + fieldName + " 은 null 이어야 합니다.");
        }
    }
}
