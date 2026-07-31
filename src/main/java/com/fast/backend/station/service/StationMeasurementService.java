package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.TippingLevel;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationMeasurementResponseMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 측정 세션과 측정 결과의 수신·검증·저장·조회·브로드캐스트(prompt95, prompt96).
 *
 * <p><b>수신 경로는 REST 뿐이다.</b> 옛 {@code fast/station/{station_id}/measurement} MQTT 구독은
 * 제거됐다. MQTT 시절에는 발행자에게 응답할 방법이 없어 검증 실패를 내부에서 삼키고 로그만 남겼지만,
 * REST 는 호출자(측정 데스크탑)가 결과를 알아야 재전송·재측정을 판단할 수 있으므로
 * {@link BusinessException}을 <b>삼키지 않고</b> 그대로 던져 {@code GlobalExceptionHandler}가
 * HTTP 상태로 옮기게 한다.
 *
 * <p><b>세션 규칙(prompt96)</b>
 * <ol>
 *   <li>설비는 하나뿐이라 <b>동시에 활성 세션은 1개</b>다.</li>
 *   <li>한 세션에 저장되는 <b>최종 측정 결과는 1건</b>이다.</li>
 *   <li>측정 결과가 저장되기 <b>전에는 세션을 종료할 수 없다</b>. 저장된 뒤에는 status 와
 *       무관하게(DIMENSIONS_ONLY/NO_DETECTION/UNRELIABLE 포함) 종료할 수 있다 —
 *       <b>세션 종료 조건과 적재 추천 조건은 별개다.</b></li>
 * </ol>
 *
 * <p><b>백엔드는 판정하지 않는다.</b> 전복 등급·돌출률은 측정 데스크탑이 계산해 보낸 값을 검증·정규화해
 * 저장만 한다. 값이 없는 status 에서 임의의 기본값을 채워 넣지 않는다.
 *
 * <p>동시성 방어는 전부 <b>DB 조건부 UPDATE·UNIQUE 제약</b>으로 한다 — {@code synchronized}는
 * 인스턴스가 여러 개면 무력하다.
 */
@Service
public class StationMeasurementService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementService.class);

    private static final int MAX_MEASUREMENT_ID_LENGTH = 100;

    private final StationMeasurementMapper measurementMapper;
    private final StationSessionMapper sessionMapper;
    private final StationMeasurementResponseMapper responseMapper;
    private final StationMeasurementPlacementEligibility placementEligibility;
    private final StationMeasurementBroadcaster broadcaster;

    public StationMeasurementService(StationMeasurementMapper measurementMapper,
            StationSessionMapper sessionMapper,
            StationMeasurementResponseMapper responseMapper,
            StationMeasurementPlacementEligibility placementEligibility,
            StationMeasurementBroadcaster broadcaster) {
        this.measurementMapper = measurementMapper;
        this.sessionMapper = sessionMapper;
        this.responseMapper = responseMapper;
        this.placementEligibility = placementEligibility;
        this.broadcaster = broadcaster;
    }

    // ── 세션 ────────────────────────────────────────────────────────────────

    /**
     * 화물 하나에 대한 측정 세션을 연다.
     *
     * <p>점유를 조건부 UPDATE 한 문장으로 잡는 이유: SELECT 로 "비었는지" 확인한 뒤 UPDATE 하면
     * 두 요청이 같은 빈 상태를 보고 <b>둘 다</b> 성공할 수 있다. WHERE 절에 조건을 넣어 DB 가 한 번에
     * 판정하게 하고 영향 행 수로 승패를 가른다.
     */
    @Transactional
    public StationSession openSession(String cargoId) {
        if (cargoId == null || cargoId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "cargoId 는 필수입니다.");
        }
        String sessionId = UUID.randomUUID().toString();
        sessionMapper.insert(new StationSession(sessionId, cargoId));
        if (sessionMapper.acquireStation(sessionId) == 0) {
            throw new BusinessException(ErrorCode.STATION_ALREADY_OCCUPIED,
                    "측정 설비가 이미 점유 중입니다. 기존 세션을 먼저 종료하세요.");
        }
        log.info("Station session opened: sessionId={}, cargoId={}", sessionId, cargoId);
        return new StationSession(sessionId, cargoId);
    }

    /**
     * 세션을 종료한다 — <b>측정 결과가 저장된 뒤에만</b> 가능하다.
     *
     * <p>판정과 해제를 조건부 UPDATE 한 문장으로 묶는다("존재 확인 → 해제"로 나누면 그 사이에 상태가
     * 바뀔 수 있다). 영향 행이 0이면 그때 원인을 조회해 구분한다 — 전부 NOT_FOUND 로 뭉개지 않는다.
     * 종료에 실패하면 활성 세션은 <b>그대로 유지</b>된다.
     *
     * <p>세션 행 자체는 지우지 않는다 — 측정 결과가 FK 로 참조한다.
     */
    @Transactional
    public void closeSession(String sessionId) {
        if (sessionMapper.releaseStationIfMeasurementExists(sessionId) == 1) {
            log.info("Station session closed: sessionId={}", sessionId);
            return;
        }

        // 여기부터는 실패 원인 규명 전용이다 — 상태를 바꾸지 않는다.
        StationSession active = sessionMapper.findActiveSession().orElse(null);
        if (active == null || !active.getSessionId().equals(sessionId)) {
            throw new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                    "활성 세션이 아닙니다: " + sessionId);
        }
        throw new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_COMPLETED,
                "측정 결과가 저장되지 않아 세션을 종료할 수 없습니다: sessionId=" + sessionId);
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
     * <p>저장(INSERT)과 "세션이 측정 완료 상태가 됨"은 <b>같은 트랜잭션</b>이다 — 완료 여부를 별도
     * 상태 컬럼이 아니라 <b>측정 행의 존재</b>로 표현하기 때문에 INSERT 하나가 곧 상태 전이다.
     * 그래서 두 값이 어긋날 수 없고 새 상태 컬럼도 필요 없다.
     *
     * <p>검증 순서에 의미가 있다: measurementId 중복을 활성 세션 조회보다 <b>먼저</b> 본다.
     * 재전송이 "세션 없음"으로 보고되면 데스크탑이 원인을 오해한다.
     */
    @Transactional
    public StationMeasurementResponse create(StationMeasurementCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID, "요청 본문이 비어 있습니다.");
        }
        String measurementId = validateMeasurementId(request.measurementId());
        StationMeasurementStatus status = StationMeasurementStatus.fromRaw(request.status())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                        "알 수 없는 status 값입니다: " + request.status()));
        TippingLevel tippingLevel = validateByStatus(request, status);

        if (measurementMapper.existsByMeasurementId(measurementId)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_ID_DUPLICATED,
                    "이미 저장된 measurement_id 입니다: " + measurementId);
        }

        StationSession session = sessionMapper.findActiveSession()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                        "활성 세션이 없어 측정 결과를 저장할 수 없습니다: measurementId=" + measurementId));

        // 세션당 최종 결과는 1건. 사전 확인은 빠른 실패용이고, 경합의 최종 방어는
        // uk_station_measurement_session UNIQUE 제약이다(동시 요청은 DB 가 막는다).
        if (measurementMapper.existsBySessionId(session.getSessionId())) {
            throw new BusinessException(ErrorCode.STATION_SESSION_MEASUREMENT_ALREADY_EXISTS,
                    "이 세션에는 이미 측정 결과가 저장되어 있습니다: sessionId=" + session.getSessionId());
        }

        LocalDateTime receivedAt = LocalDateTime.now();
        StationMeasurement entity = new StationMeasurement();
        entity.setMeasurementId(measurementId);
        entity.setSessionId(session.getSessionId());
        entity.setStatus(status);
        entity.setCargoHeight(request.cargoHeight());
        // 소문자 입력을 대문자로 정규화해 저장한다 — 비교하는 쪽이 표기를 신경 쓰지 않게 한다.
        entity.setTippingLevel(tippingLevel == null ? null : tippingLevel.name());
        entity.setOverhangRatio(request.overhangRatio());
        entity.setCreatedAt(receivedAt);
        entity.setReceivedAt(receivedAt);
        if (request.measuredAt() != null) {
            // 장비 측정 시각을 UTC + 오프셋 분으로 나눠 보존한다(DATETIME 은 타임존을 못 담는다).
            entity.setMeasuredAtUtc(
                    request.measuredAt().withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime());
            entity.setMeasuredAtOffsetMinutes(request.measuredAt().getOffset().getTotalSeconds() / 60);
        }
        measurementMapper.insert(entity);

        boolean eligible = placementEligibility.isEligible(entity);
        log.info("Station measurement stored: measurementId={}, sessionId={}, cargoId={}, status={}, "
                        + "cargoHeightM={}, tippingLevel={}, overhangRatio={}, placementEligible={}",
                entity.getMeasurementId(), session.getSessionId(), session.getCargoId(), status,
                entity.getCargoHeight(), entity.getTippingLevel(), entity.getOverhangRatio(), eligible);

        StationMeasurementResponse response =
                responseMapper.toResponse(entity, session.getCargoId(), eligible);
        broadcaster.broadcast(response, receivedAt);
        return response;
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public StationMeasurementResponse findByMeasurementId(String measurementId) {
        StationMeasurement m = measurementMapper.findByMeasurementId(measurementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "존재하지 않는 측정 결과입니다: " + measurementId));
        return toResponse(m);
    }

    @Transactional(readOnly = true)
    public StationMeasurementResponse findLatestBySessionId(String sessionId) {
        StationMeasurement m = measurementMapper.findLatestBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "세션의 측정 결과가 없습니다: " + sessionId));
        return toResponse(m);
    }

    /** MQTT 시절에 저장된 행 조회용 레거시 경로. REST 로 저장된 행은 station_id 가 없다. */
    @Transactional(readOnly = true)
    public StationMeasurementResponse findLatestByStationId(String stationId) {
        StationMeasurement m = measurementMapper.findLatestByStationId(stationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "스테이션의 측정 결과가 없습니다: " + stationId));
        return toResponse(m);
    }

    /**
     * 적재 추천에 쓸 측정 결과를 꺼낸다 — <b>안전 조건을 만족할 때만</b> 돌려준다.
     *
     * <p>추천 진입점이 이 메서드를 통과하게 두어 "조건 미충족인데 슬롯을 찾아본" 상황이 아예 생기지
     * 않게 한다. 조건 미충족은 원인별 오류로 드러난다(조용한 빈 결과가 아니다).
     */
    @Transactional(readOnly = true)
    public StationMeasurement requirePlacementEligibleMeasurement(String sessionId) {
        StationMeasurement m = measurementMapper.findLatestBySessionId(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "세션의 측정 결과가 없습니다: " + sessionId));
        placementEligibility.requireEligible(m);
        return m;
    }

    private StationMeasurementResponse toResponse(StationMeasurement m) {
        String cargoId = m.getSessionId() == null ? null
                : sessionMapper.findBySessionId(m.getSessionId())
                        .map(StationSession::getCargoId)
                        .orElse(null);
        return responseMapper.toResponse(m, cargoId, placementEligibility.isEligible(m));
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
     * status 별 필드 조합을 검증하고 정규화된 전복 등급을 돌려준다.
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
                // 치수는 쟀지만 파렛트를 못 찾아 전복·돌출 판정이 불가능한 상태다.
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

    /**
     * 저장 단계에서는 돌출률 상한을 두지 않는다 — 화물이 파렛트보다 넓으면 1.0 을 넘을 수 있고 그것도
     * 사실이므로 기록한다. 적재 추천 가능 여부는 별개 게이트가 판단한다.
     */
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
