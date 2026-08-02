package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.config.StationSessionProperties;
import com.fast.backend.station.domain.StationSession;
import com.fast.backend.station.domain.StationState;
import com.fast.backend.station.domain.TippingLevel;
import com.fast.backend.station.dto.StationMeasurementCreateRequest;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.mapper.StationMeasurementResponseMapper;
import com.fast.backend.station.mapper.StationSessionMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.transport.service.TransportTaskMeasurementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 측정 세션과 측정 결과의 수신·검증·저장·조회·브로드캐스트.
 *
 * <p>측정 결과는 REST로 들어오며 이 서비스가 세션 검증·저장을 담당한다.
 *
 * <p><b>세션 규칙</b>
 * <ol>
 *   <li>설비는 하나뿐이라 <b>동시에 활성 세션은 1개</b>다.</li>
 *   <li>한 세션에 저장되는 <b>최종 측정 결과는 1건</b>이다.</li>
 *   <li>최종 결과를 저장하는 트랜잭션 안에서 세션을 자동 해제한다.</li>
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
    /** station_session.session_id 컬럼 길이와 같다 — DB 가 자르기 전에 400 으로 돌려준다. */
    private static final int MAX_SESSION_ID_LENGTH = 100;

    private final StationMeasurementMapper measurementMapper;
    private final StationSessionMapper sessionMapper;
    private final StationMeasurementResponseMapper responseMapper;
    private final StationMeasurementPlacementEligibility placementEligibility;
    private final StationMeasurementBroadcaster broadcaster;
    private final CargoMapper cargoMapper;
    private final TransportTaskMapper transportTaskMapper;
    private final StationSessionProperties sessionProperties;
    private final TransportTaskMeasurementService transportTaskMeasurementService;
    private final Clock clock;

    public StationMeasurementService(StationMeasurementMapper measurementMapper,
            StationSessionMapper sessionMapper,
            StationMeasurementResponseMapper responseMapper,
            StationMeasurementPlacementEligibility placementEligibility,
            StationMeasurementBroadcaster broadcaster,
            CargoMapper cargoMapper,
            TransportTaskMapper transportTaskMapper,
            StationSessionProperties sessionProperties,
            TransportTaskMeasurementService transportTaskMeasurementService,
            Clock clock) {
        this.measurementMapper = measurementMapper;
        this.sessionMapper = sessionMapper;
        this.responseMapper = responseMapper;
        this.placementEligibility = placementEligibility;
        this.broadcaster = broadcaster;
        this.cargoMapper = cargoMapper;
        this.transportTaskMapper = transportTaskMapper;
        this.sessionProperties = sessionProperties;
        this.transportTaskMeasurementService = transportTaskMeasurementService;
        this.clock = clock;
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

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime expiredBefore = now.minus(sessionProperties.ttl());

        // 점유를 빼앗기 전에 "무엇을 회수하는지" 먼저 읽어 둔다. 로그 전용이며 판정에는 쓰지 않는다 —
        // 판정은 아래 조건부 UPDATE 가 단독으로 한다(이 조회와 UPDATE 사이에 상태가 바뀌어도 안전).
        StationState before = sessionMapper.findState().orElse(null);

        Cargo cargo = new Cargo();
        cargo.setCargoId(cargoId);
        cargo.setCreatedAt(now);
        cargoMapper.insertIfAbsent(cargo);

        String sessionId = UUID.randomUUID().toString();
        sessionMapper.insert(new StationSession(sessionId, cargoId));

        if (sessionMapper.acquireStation(sessionId, now, expiredBefore) == 0) {
            throw new BusinessException(ErrorCode.STATION_ALREADY_OCCUPIED,
                    "측정 설비가 이미 점유 중입니다. 기존 세션을 먼저 종료하세요.");
        }

        // 차량 도착 요청으로 대기 중인 작업이 있으면 AI가 연 세션을 그 작업에 연결한다.
        // 수동 측정처럼 연결할 작업이 없는 경우에는 독립 세션으로 그대로 허용한다.
        transportTaskMapper.findPendingMeasurementByCargoId(cargoId).ifPresent(task -> {
            if (transportTaskMapper.startMeasurement(task.getId(), sessionId) != 1) {
                throw new IllegalStateException(
                        "Failed to bind the station session to transport task: " + task.getTaskCode());
            }
        });

        if (before != null && before.isOccupied() && before.isExpired(expiredBefore)) {
            // 스케줄러 실행 사이에 직접 새 세션이 만료 점유를 회수한 경우에도 이전 작업을 남기지 않는다.
            transportTaskMapper.findByMeasurementSessionId(before.getActiveSessionId()).ifPresent(task ->
                    transportTaskMapper.updateStatusIfCurrent(
                            task.getId(), com.fast.backend.transport.domain.TaskStatus.MEASURING,
                            com.fast.backend.transport.domain.TaskStatus.FAILED,
                            null, now));
            // 정상 종료를 못 하고 죽은 세션을 회수한 경우다. 조용히 넘어가면 "왜 남의 세션이
            // 끊겼는지" 추적할 수 없으므로 WARN 으로 남긴다.
            log.warn("Station session expired and released: expiredSessionId={}, acquiredAt={}, "
                            + "expiredAt={}, ttlSeconds={}, newSessionId={}",
                    before.getActiveSessionId(), before.getAcquiredAt(), now,
                    sessionProperties.ttlSeconds(), sessionId);
        }

        log.info("Station session acquired: sessionId={}, cargoId={}, acquiredAt={}",
                sessionId, cargoId, now);
        return new StationSession(sessionId, cargoId);
    }

    /**
     * 기존 AI 클라이언트가 측정 블록 종료 시 호출하는 호환 API다.
     * 측정 결과 저장 시 세션은 이미 자동 해제되므로 일반적으로
     * {@code STATION_SESSION_NOT_ACTIVE}가 반환되며, AI는 이를 정상 종료로 처리한다.
     */
    @Transactional
    public void closeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sessionId 는 필수입니다.");
        }

        StationState state = sessionMapper.findStateForUpdate()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE));
        if (!state.isOccupied() || !sessionId.equals(state.getActiveSessionId())) {
            throw new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                    "이미 종료됐거나 현재 활성 세션이 아닙니다: " + sessionId);
        }
        if (!measurementMapper.existsBySessionId(sessionId)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_COMPLETED,
                    "측정 결과가 없는 활성 세션은 TTL 만료 시 자동 종료됩니다: " + sessionId);
        }
        if (sessionMapper.releaseStation(sessionId) != 1) {
            throw new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE);
        }
    }

    @Transactional(readOnly = true)
    public StationSession findActiveSession() {
        return sessionMapper.findActiveSession()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                        "활성화된 측정 세션이 없습니다."));
    }

    // ── 결과 저장 ───────────────────────────────────────────────────────────

    /**
     * REST로 받은 측정 결과를 저장하고 활성 세션을 해제한다.
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
        String requestedSessionId = validateSessionId(request.sessionId());
        String measurementId = validateMeasurementId(request.measurementId());
        StationMeasurementStatus status = StationMeasurementStatus.fromRaw(request.status())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                        "알 수 없는 status 값입니다: " + request.status()));
        TippingLevel tippingLevel = validateByStatus(request, status);

        if (measurementMapper.existsByMeasurementId(measurementId)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_ID_DUPLICATED,
                    "이미 저장된 measurement_id 입니다: " + measurementId);
        }

        // 점유 상태를 행 잠금과 함께 읽는다. 여기부터 커밋까지 acquireStation/
        // acquireStation/releaseStation 이 대기하므로, "검증 통과 후 INSERT 전에 세션이
        // 바뀌는" TOCTOU 가 발생하지 않는다.
        StationState state = sessionMapper.findStateForUpdate()
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                        "측정 설비 상태를 읽을 수 없습니다: measurementId=" + measurementId));

        if (!state.isOccupied()) {
            // TTL 만료로 이미 풀렸거나 애초에 열린 적이 없다.
            log.warn("Station measurement rejected: no active session — measurementId={}, "
                    + "requestedSessionId={}", measurementId, requestedSessionId);
            throw new BusinessException(ErrorCode.STATION_SESSION_NOT_ACTIVE,
                    "활성 세션이 없어 측정 결과를 저장할 수 없습니다: measurementId=" + measurementId);
        }

        String activeSessionId = state.getActiveSessionId();
        if (!activeSessionId.equals(requestedSessionId)) {
            // 세션 A 가 해제되고 세션 B 가 열린 뒤 도착한 A 의 늦은 측정이 여기서 걸린다.
            // 활성 세션으로 자동 재귀속하지 않는다 — 그게 이 검증의 존재 이유다.
            log.warn("Station measurement rejected: session mismatch — measurementId={}, "
                            + "requestedSessionId={}, activeSessionId={}",
                    measurementId, requestedSessionId, activeSessionId);
            throw new BusinessException(ErrorCode.STATION_SESSION_MISMATCH,
                    "측정 요청의 세션이 현재 활성 세션과 일치하지 않습니다: requested="
                            + requestedSessionId + ", active=" + activeSessionId);
        }

        StationSession session = sessionMapper.findBySessionId(requestedSessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_SESSION_NOT_FOUND,
                        "존재하지 않는 측정 세션입니다: " + requestedSessionId));

        // 세션당 최종 결과는 1건. 사전 확인은 빠른 실패용이고, 경합의 최종 방어는
        // uk_station_measurement_session UNIQUE 제약이다(동시 요청은 DB 가 막는다).
        if (measurementMapper.existsBySessionId(requestedSessionId)) {
            throw new BusinessException(ErrorCode.STATION_SESSION_MEASUREMENT_ALREADY_EXISTS,
                    "이 세션에는 이미 측정 결과가 저장되어 있습니다: sessionId=" + requestedSessionId);
        }

        LocalDateTime receivedAt = LocalDateTime.now(clock);
        StationMeasurement entity = new StationMeasurement();
        entity.setMeasurementId(measurementId);
        // 활성 세션 조회값이 아니라 **요청이 밝힌 세션**을 저장한다. 위에서 두 값이 같음을
        // 잠금 아래에서 확인했으므로 동일하지만, 자동 귀속이 되살아나지 않도록 출처를 명시한다.
        entity.setSessionId(requestedSessionId);
        entity.setStatus(status);
        entity.setCargoHeight(request.cargoHeight());
        // 소문자 입력을 대문자로 정규화해 저장한다 — 비교하는 쪽이 표기를 신경 쓰지 않게 한다.
        entity.setTippingLevel(tippingLevel == null ? null : tippingLevel.name());
        entity.setOverhangRatio(request.overhangRatio());
        entity.setCreatedAt(receivedAt);
        measurementMapper.insert(entity);

        if (sessionMapper.releaseStation(requestedSessionId) != 1) {
            throw new IllegalStateException(
                    "Failed to release the station session after saving measurement: " + requestedSessionId);
        }

        boolean eligible = placementEligibility.isEligible(entity);
        transportTaskMeasurementService.complete(session, entity);
        log.info("Station measurement accepted: measurementId={}, sessionId={}, cargoId={}, status={}, "
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

    /**
     * 요청 sessionId 검증. 누락·공백은 400 이다 — 구버전 클라이언트를 위해 값을
     * 추측하거나 활성 세션으로 대체하지 않는다. 그렇게 하면 막으려던 오귀속이 그대로 발생한다.
     */
    private String validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "sessionId 는 필수입니다. 세션 생성 응답의 sessionId 를 그대로 보내야 합니다.");
        }
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "sessionId 는 " + MAX_SESSION_ID_LENGTH + "자 이하여야 합니다: " + sessionId.length());
        }
        return sessionId;
    }

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
