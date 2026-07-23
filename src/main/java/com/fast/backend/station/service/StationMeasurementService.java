package com.fast.backend.station.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.station.adapter.StationMeasurementAdapter;
import com.fast.backend.station.domain.StationDirection;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementBox;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementResponse;
import com.fast.backend.station.mapper.StationMeasurementBoxMapper;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.websocket.StationMeasurementBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 측정 스테이션 측정 결과(v1.0) 수신·검증·저장·조회·브로드캐스트를 담당한다(prompt16.md 8단계, MR !36,
 * FR-101-5). {@code fast/station/{station_id}/measurement} MQTT 토픽이 유일한 수신 경로이며
 * ({@code MqttMessageRouter}), 새 결과 수신은 REST로 받지 않는다 — 조회만 REST로 노출한다.
 * 기존 {@code AiCargoAnalysisService}와 동일한 방어·트랜잭션 패턴을 따르되 스테이션 도메인 전용으로
 * 완전히 분리했다(원칙 3·5번).
 *
 * <p>검증 실패·중복은 {@link BusinessException}으로 표현하고 이 메서드 안에서 잡아 경고 로그만 남긴다
 * (MQTT 소비 스레드를 죽이지 않음, 메시지 하나만 폐기). 반면 부모 insert 이후 박스 insert 단계의
 * 예상치 못한 {@link RuntimeException}(DB 오류 등)은 <b>일부러 잡지 않는다</b> — {@code @Transactional}
 * 경계를 빠져나가야 Spring이 부모·자식을 함께 롤백해 부분 성공을 막는다. 그 예외를 최종적으로 잡아 MQTT
 * 스레드를 보호하는 책임은 호출자({@code MqttMessageRouter})에 있다.
 */
@Service
public class StationMeasurementService {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementService.class);

    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final int REQUIRED_MINIATURE_SCALE = 10;
    private static final int MAX_DIRECTIONS = 2;
    private static final double TOLERANCE = 0.001;

    private final StationMeasurementMapper measurementMapper;
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
        }
    }

    @Transactional(readOnly = true)
    public StationMeasurementResponse findByMeasurementId(String measurementId) {
        StationMeasurement m = measurementMapper.findByMeasurementId(measurementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "존재하지 않는 측정 결과입니다: " + measurementId));
        return adapter.toResponse(m, boxMapper.findByStationMeasurementId(m.getId()));
    }

    @Transactional(readOnly = true)
    public StationMeasurementResponse findLatestByStationId(String stationId) {
        StationMeasurement m = measurementMapper.findLatestByStationId(stationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_NOT_FOUND,
                        "스테이션의 측정 결과가 없습니다: " + stationId));
        return adapter.toResponse(m, boxMapper.findByStationMeasurementId(m.getId()));
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    /** 전체 검증. 통과하면 정규화된 status를 반환하고, 실패하면 {@link BusinessException}을 던진다. */
    StationMeasurementStatus validate(StationMeasurementMessage message) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(message.schemaVersion())) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_SCHEMA_VERSION_UNSUPPORTED,
                    "지원하지 않는 schema_version입니다: " + message.schemaVersion());
        }
        requireNonBlank(message.measurementId(), "measurement_id는 필수입니다.");
        requireNonBlank(message.stationId(), "station_id는 필수입니다.");
        if (message.measuredAt() == null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID, "measured_at은 필수입니다.");
        }
        StationMeasurementStatus status = StationMeasurementStatus.fromRaw(message.status())
                .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                        "알 수 없는 status 값입니다: " + message.status()));

        if (status == StationMeasurementStatus.OK) {
            if (message.detection() == null || message.distance() == null
                    || message.dimensions() == null || message.loadBalance() == null) {
                throw new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                        "status가 ok이면 detection/distance/dimensions/load_balance가 모두 필요합니다.");
            }
        } else {
            // no_detection 또는 unreliable: dimensions/load_balance는 반드시 null(정책 2번).
            // detection/distance는 선택(정책 모호 → 선택으로 구현, 문서에 명시).
            if (message.dimensions() != null || message.loadBalance() != null) {
                throw new BusinessException(ErrorCode.STATION_MEASUREMENT_STATUS_INVALID,
                        "status가 ok가 아니면 dimensions/load_balance는 null이어야 합니다.");
            }
        }

        validateDetection(message.detection());
        validateDistance(message.distance());
        validateDimensions(message.dimensions());
        validateLoadBalance(message.loadBalance());
        return status;
    }

    private void validateDetection(StationMeasurementMessage.Detection detection) {
        if (detection == null) {
            return;
        }
        List<StationMeasurementMessage.DetectedBox> boxes =
                detection.boxes() == null ? List.of() : detection.boxes();
        if (detection.boxCount() != null && detection.boxCount() != boxes.size()) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DETECTION_INVALID,
                    "box_count(" + detection.boxCount() + ")가 boxes 개수(" + boxes.size() + ")와 다릅니다.");
        }
        for (StationMeasurementMessage.DetectedBox box : boxes) {
            validateBbox(box.bboxPx());
            validateScore(box.score());
        }
        StationMeasurementMessage.PalletDetection pallet = detection.pallet();
        if (pallet != null) {
            validateBbox(pallet.bboxPx());
            validateScore(pallet.score());
        }
    }

    /** bbox_px는 nullable. 존재하면 정확히 4개, 각 값 0 이상(정책 7번·11번, prompt16.md 4단계). */
    private void validateBbox(List<Integer> bboxPx) {
        if (bboxPx == null) {
            return;
        }
        if (bboxPx.size() != 4 || bboxPx.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DETECTION_INVALID,
                    "bbox_px는 존재할 경우 null이 아닌 정수 4개([x, y, width, height])여야 합니다.");
        }
        for (Integer v : bboxPx) {
            if (v < 0) {
                throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DETECTION_INVALID,
                        "bbox_px의 각 값은 0 이상이어야 합니다.");
            }
        }
    }

    private void validateScore(Double score) {
        if (score == null) {
            return;
        }
        if (!isFinite(score) || score < 0.0 || score > 1.0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DETECTION_INVALID,
                    "score는 0.0~1.0 범위여야 합니다.");
        }
    }

    private void validateDistance(StationMeasurementMessage.Distance distance) {
        if (distance == null) {
            return;
        }
        if (distance.frontCm() == null || !isFinite(distance.frontCm()) || distance.frontCm() <= 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DISTANCE_INVALID, "front_cm은 0보다 커야 합니다.");
        }
        if (distance.stdCm() == null || !isFinite(distance.stdCm()) || distance.stdCm() < 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DISTANCE_INVALID, "std_cm은 0 이상이어야 합니다.");
        }
        if (distance.framesUsed() == null || distance.framesUsed() <= 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DISTANCE_INVALID, "frames_used는 0보다 커야 합니다.");
        }
    }

    private void validateDimensions(StationMeasurementMessage.Dimensions dimensions) {
        if (dimensions == null) {
            return;
        }
        if (dimensions.heightCm() == null || !isFinite(dimensions.heightCm()) || dimensions.heightCm() <= 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID, "height_cm은 0보다 커야 합니다.");
        }
        if (dimensions.widthCm() == null || !isFinite(dimensions.widthCm()) || dimensions.widthCm() <= 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID, "width_cm은 0보다 커야 합니다.");
        }
        // depth_cm은 반드시 null(정책 3번, 2026-07-22 확정) — 값이 오면 거부한다.
        if (dimensions.depthCm() != null) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID,
                    "depth_cm은 항상 null이어야 합니다(정면 단일 카메라로 깊이 측정 불가).");
        }
        if (dimensions.miniatureScale() == null || dimensions.miniatureScale() != REQUIRED_MINIATURE_SCALE) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID,
                    "miniature_scale은 " + REQUIRED_MINIATURE_SCALE + "이어야 합니다.");
        }
        // 실물 cm ÷ 10 = miniature cm, 이를 mm로 환산하면 원 실물 cm 수치와 같다(정책 4번).
        if (dimensions.miniatureHeightMm() == null
                || !numericallyEqual(dimensions.miniatureHeightMm(), dimensions.heightCm())) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID,
                    "miniature_height_mm은 height_cm과 수치적으로 같아야 합니다.");
        }
        if (dimensions.miniatureWidthMm() == null
                || !numericallyEqual(dimensions.miniatureWidthMm(), dimensions.widthCm())) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_DIMENSIONS_INVALID,
                    "miniature_width_mm은 width_cm과 수치적으로 같아야 합니다.");
        }
    }

    private void validateLoadBalance(StationMeasurementMessage.LoadBalance loadBalance) {
        if (loadBalance == null) {
            return;
        }
        List<StationDirection> directions = parseDirections(loadBalance);
        // parseDirections가 개수(0~2)·중복·미지원 값을 검증한다.

        Double ratioX = loadBalance.ratioX();
        Double ratioY = loadBalance.ratioY();
        if (ratioX == null || !isFinite(ratioX) || ratioY == null || !isFinite(ratioY)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                    "ratio_x, ratio_y는 finite 값이어야 합니다.");
        }
        Double threshold = loadBalance.threshold();
        if (threshold == null || !isFinite(threshold) || threshold < 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID, "threshold는 0 이상이어야 합니다.");
        }
        Double magnitude = loadBalance.magnitude();
        if (magnitude == null || !isFinite(magnitude) || magnitude < 0) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID, "magnitude는 0 이상이어야 합니다.");
        }
        double expectedMagnitude = Math.max(Math.abs(ratioX), Math.abs(ratioY));
        if (!numericallyEqual(magnitude, expectedMagnitude)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                    "magnitude는 max(abs(ratio_x), abs(ratio_y))와 같아야 합니다.");
        }
        Boolean eccentric = loadBalance.eccentric();
        if (eccentric == null || eccentric != (expectedMagnitude > threshold)) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                    "eccentric은 max(abs(ratio_x), abs(ratio_y)) > threshold와 일치해야 합니다.");
        }
        // directions는 위에서 이미 검증됨(사용만 하고 버림 — 저장은 process에서 다시 parse).
        if (directions.size() > MAX_DIRECTIONS) { // 방어적: parseDirections가 이미 보장
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                    "direction은 최대 " + MAX_DIRECTIONS + "개까지 허용됩니다.");
        }
    }

    /** direction 배열을 파싱하며 개수(0~2)·미지원 값·중복을 검증한다. */
    private List<StationDirection> parseDirections(StationMeasurementMessage.LoadBalance loadBalance) {
        if (loadBalance == null || loadBalance.direction() == null) {
            return List.of();
        }
        List<String> raw = loadBalance.direction();
        if (raw.size() > MAX_DIRECTIONS) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                    "direction은 최대 " + MAX_DIRECTIONS + "개까지 허용됩니다.");
        }
        List<StationDirection> parsed = new ArrayList<>();
        for (String value : raw) {
            StationDirection direction = StationDirection.fromRaw(value)
                    .orElseThrow(() -> new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                            "알 수 없는 direction 값입니다: " + value));
            if (parsed.contains(direction)) {
                throw new BusinessException(ErrorCode.STATION_MEASUREMENT_LOAD_BALANCE_INVALID,
                        "direction에 중복된 값이 있습니다: " + value);
            }
            parsed.add(direction);
        }
        return parsed;
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.STATION_MEASUREMENT_INVALID, message);
        }
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private boolean numericallyEqual(double a, double b) {
        return Math.abs(a - b) <= TOLERANCE;
    }
}
