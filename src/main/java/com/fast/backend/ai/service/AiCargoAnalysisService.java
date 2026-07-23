package com.fast.backend.ai.service;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.domain.AiCargoAnalysis;
import com.fast.backend.ai.domain.AiCargoDetectionBox;
import com.fast.backend.ai.domain.DimensionScale;
import com.fast.backend.ai.domain.LoadBalanceDirection;
import com.fast.backend.ai.dto.AiCargoAnalysisMessage;
import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import com.fast.backend.ai.mapper.AiCargoAnalysisMapper;
import com.fast.backend.ai.mapper.AiCargoDetectionBoxMapper;
import com.fast.backend.ai.websocket.AiCargoAnalysisBroadcaster;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code cargo/detected} MQTT 토픽으로 수신되는 AI 화물·파렛트 분석 결과를 검증·저장하고 조회·WebSocket
 * 브로드캐스트를 담당한다(prompt26.md 11장). {@link com.fast.backend.mqtt.inbound.MqttMessageRouter}가
 * 유일한 MQTT 진입점이며, REST로는 이 클래스가 조회 전용({@link #findByAnalysisId}, {@link #findLatestByCargoId})으로만
 * 노출된다 — 새 결과 수신은 REST로 받지 않는다(prompt26.md 6장 "1. 기존 MQTT subscriber 확장" 우선순위를
 * 따름, {@code cargo/detected} 토픽이 이미 구독 중이었고 {@code MqttMessageRouter.routeCargoDetected()}가
 * "규격이 확정되면 확장하라"는 미리 마련된 확장 지점이었다).
 *
 * <p><b>dimensions.scale 처리 방식(prompt26.md 2.4장 방식 A vs B)</b>: 이 프로젝트는 "방식 B"를 선택했다 —
 * AI가 이미 scale에 맞는 완성값(REAL이면 실측값, MINIATURE면 실측값÷10 축소값)을 계산해서 보내고,
 * 백엔드는 그 값을 그대로 저장·응답한다. 이유: 축소 변환은 순수 표시 단위 문제라 AI가 dimensions를
 * 만드는 시점에 이미 알고 있는 정보(축척)로 계산하는 편이 자연스럽고, 백엔드가 조회 시점마다 scale을
 * 보고 나눗셈을 반복하면 "저장된 값의 의미"가 scale 필드 하나에 암묵적으로 의존하게 되어 응답 DTO
 * 구성 로직이 더 복잡해진다. 백엔드는 scale을 검증(REAL/MINIATURE 중 하나인지)만 하고 변환은 하지 않는다.
 *
 * <p><b>참조 무결성(prompt26.md 5장 11번 분석 결과)</b>: 이 저장소에는 cargo/pallet/task 도메인 테이블이
 * 아직 없다(전체 코드베이스 검색 결과 0건). 따라서 {@code cargoId}는 물론 {@code vehicleId}도 FK나
 * 존재 확인 없이 느슨한 문자열로만 저장한다 — vehicleId만 {@code VehicleMapper}로 존재 확인하면
 * cargoId/palletId/taskId와의 검증 수준이 들쭉날쭉해지므로, 참조 대상 테이블이 아예 없는 현재 상태에서는
 * 모든 식별자를 동일하게(검증 없이) 다루는 쪽이 일관적이라고 판단했다.
 */
@Service
public class AiCargoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiCargoAnalysisService.class);

    /** 현재 지원하는 유일한 스키마 버전(prompt26.md 2.7장). */
    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";
    private static final int MAX_LOAD_DIRECTIONS = 2;

    private final AiCargoAnalysisMapper aiCargoAnalysisMapper;
    private final AiCargoDetectionBoxMapper aiCargoDetectionBoxMapper;
    private final AiCargoAnalysisBroadcaster broadcaster;

    public AiCargoAnalysisService(
            AiCargoAnalysisMapper aiCargoAnalysisMapper,
            AiCargoDetectionBoxMapper aiCargoDetectionBoxMapper,
            AiCargoAnalysisBroadcaster broadcaster) {
        this.aiCargoAnalysisMapper = aiCargoAnalysisMapper;
        this.aiCargoDetectionBoxMapper = aiCargoDetectionBoxMapper;
        this.broadcaster = broadcaster;
    }

    /**
     * 처리 흐름(prompt26.md 11장): schemaVersion 검증 → 필수값 검증 → status 정규화 → 상태별 정합성
     * 검증 → bbox/거리/dimensions/loadBalance/ratios 검증 → 중복 analysisId 확인 → 분석 결과 저장 →
     * 감지 박스 저장 → 응답 변환 → WebSocket 브로드캐스트.
     *
     * <p>검증 실패·중복은 {@link BusinessException}으로 표현하고 이 메서드 안에서 잡아 경고 로그만
     * 남긴다(MQTT 소비 스레드를 죽이지 않음, prompt26.md 10장·15장) — 이 시점까지는 아직 DB에 아무것도
     * 쓰지 않았으므로 여기서 예외를 삼겨도 데이터 정합성 문제가 없다. 반면 분석 결과 insert 이후
     * 박스 insert 단계에서 발생하는 예상치 못한 {@link RuntimeException}(DB 오류 등)은 <b>일부러 잡지
     * 않는다</b> — {@code @Transactional} 경계를 그대로 빠져나가야 Spring이 트랜잭션을 롤백해
     * "분석 결과만 반영되고 박스는 빠지는" 부분 성공을 막을 수 있다(prompt26.md 9.4장). 그 예외를
     * 최종적으로 잡아 MQTT 스레드를 보호하는 책임은 호출자인
     * {@link com.fast.backend.mqtt.inbound.MqttMessageRouter#routeCargoDetected}에 있다.
     */
    @Transactional
    public void process(AiCargoAnalysisMessage message) {
        try {
            validateSchemaVersion(message.schemaVersion());
            requireNonBlank(message.analysisId(), "analysisId는 필수입니다.");
            requireNonNull(message.processedAt(), "processedAt은 필수입니다.");
            AiAnalysisStatus status = validateStatus(message.status());
            List<ValidatedBox> boxes = validateBoxes(message);
            validateStatusConsistency(status, boxes, message);
            validateDistance(message.distance());
            validateDimensions(message.dimensions());
            List<LoadBalanceDirection> directions = validateLoadBalance(message.loadBalance());
            validateRatios(message.ratios());

            if (aiCargoAnalysisMapper.existsByAnalysisId(message.analysisId())) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_ID_DUPLICATED,
                        "이미 저장된 analysisId입니다: " + message.analysisId());
            }

            LocalDateTime receivedAt = LocalDateTime.now();
            AiCargoAnalysis analysis = toEntity(message, status, directions, receivedAt);
            aiCargoAnalysisMapper.insert(analysis);

            List<AiCargoDetectionBox> boxEntities = new ArrayList<>();
            for (ValidatedBox box : boxes) {
                AiCargoDetectionBox entity = new AiCargoDetectionBox();
                entity.setAnalysisId(analysis.getId());
                entity.setClassName(box.className());
                entity.setConfidence(box.confidence());
                entity.setBboxX(box.x());
                entity.setBboxY(box.y());
                entity.setBboxWidth(box.width());
                entity.setBboxHeight(box.height());
                entity.setCreatedAt(receivedAt);
                aiCargoDetectionBoxMapper.insert(entity);
                boxEntities.add(entity);
            }

            log.info("AI cargo analysis stored: analysisId={}, cargoId={}, status={}, boxes={}",
                    analysis.getAnalysisId(), analysis.getCargoId(), status, boxEntities.size());

            AiCargoAnalysisResponse response = toResponse(analysis, boxEntities);
            broadcaster.broadcast(response);
        } catch (BusinessException e) {
            log.warn("AI cargo analysis message rejected: analysisId={}, errorCode={}, message={}",
                    message.analysisId(), e.getErrorCode(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public AiCargoAnalysisResponse findByAnalysisId(String analysisId) {
        AiCargoAnalysis analysis = aiCargoAnalysisMapper.findByAnalysisId(analysisId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_ANALYSIS_NOT_FOUND,
                        "존재하지 않는 분석 결과입니다: " + analysisId));
        List<AiCargoDetectionBox> boxes = aiCargoDetectionBoxMapper.findByAnalysisId(analysis.getId());
        return toResponse(analysis, boxes);
    }

    @Transactional(readOnly = true)
    public AiCargoAnalysisResponse findLatestByCargoId(String cargoId) {
        AiCargoAnalysis analysis = aiCargoAnalysisMapper.findLatestByCargoId(cargoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_ANALYSIS_NOT_FOUND,
                        "화물의 분석 결과가 없습니다: " + cargoId));
        List<AiCargoDetectionBox> boxes = aiCargoDetectionBoxMapper.findByAnalysisId(analysis.getId());
        return toResponse(analysis, boxes);
    }

    private void validateSchemaVersion(String schemaVersion) {
        if (!SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_SCHEMA_VERSION_UNSUPPORTED,
                    "지원하지 않는 schemaVersion입니다: " + schemaVersion);
        }
    }

    private AiAnalysisStatus validateStatus(String raw) {
        return AiAnalysisStatus.fromRaw(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_ANALYSIS_STATUS_INVALID,
                        "알 수 없는 status 값입니다: " + raw));
    }

    /**
     * status별 정합성(prompt26.md 8.2장). ok는 boxes 1개 이상을 요구하고, no_detection은 boxes가
     * 비어 있고 dimensions/loadBalance/ratios가 전부 null이어야 한다. unreliable은 추가 제약이 없다
     * (distance/detection이 있어도 되고 없어도 됨).
     */
    private void validateStatusConsistency(
            AiAnalysisStatus status, List<ValidatedBox> boxes, AiCargoAnalysisMessage message) {
        if (status == AiAnalysisStatus.OK) {
            if (boxes.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_STATUS_INVALID,
                        "status가 ok이면 detection.boxes가 1개 이상이어야 합니다.");
            }
        } else if (status == AiAnalysisStatus.NO_DETECTION) {
            if (!boxes.isEmpty()) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_STATUS_INVALID,
                        "status가 no_detection이면 detection.boxes는 비어 있어야 합니다.");
            }
            if (message.dimensions() != null || message.loadBalance() != null || message.ratios() != null) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_STATUS_INVALID,
                        "status가 no_detection이면 dimensions/loadBalance/ratios는 null이어야 합니다.");
            }
        }
    }

    private List<ValidatedBox> validateBoxes(AiCargoAnalysisMessage message) {
        AiCargoAnalysisMessage.Detection detection = message.detection();
        if (detection == null || detection.boxes() == null) {
            return List.of();
        }
        List<ValidatedBox> result = new ArrayList<>();
        for (AiCargoAnalysisMessage.DetectedBox box : detection.boxes()) {
            result.add(validateBox(box));
        }
        return result;
    }

    private ValidatedBox validateBox(AiCargoAnalysisMessage.DetectedBox box) {
        List<Integer> bboxPx = box.bboxPx();
        // List.of()로 만들어진 불변 리스트는 contains(null) 자체가 NPE를 던지므로(null을 저장할 수 없다는
        // 전제로 최적화돼 있음) anyMatch로 직접 순회한다.
        if (bboxPx == null || bboxPx.size() != 4 || bboxPx.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_BBOX_INVALID,
                    "bboxPx는 null이 아닌 숫자 4개([x, y, width, height])여야 합니다.");
        }
        int x = bboxPx.get(0);
        int y = bboxPx.get(1);
        int width = bboxPx.get(2);
        int height = bboxPx.get(3);
        if (x < 0 || y < 0) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_BBOX_INVALID, "bboxPx의 x, y는 0 이상이어야 합니다.");
        }
        if (width <= 0 || height <= 0) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_BBOX_INVALID, "bboxPx의 width, height는 0보다 커야 합니다.");
        }
        Double confidence = box.confidence();
        if (confidence != null) {
            if (!isFinite(confidence)) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_BBOX_INVALID, "confidence는 NaN/Infinity를 허용하지 않습니다.");
            }
            if (confidence < 0 || confidence > 1) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_BBOX_INVALID, "confidence는 0~1 범위여야 합니다.");
            }
        }
        // className이 null/blank이면 거부 대신 "unknown"으로 정규화한다(prompt26.md 2.2장이 명시적으로
        // 허용한 두 선택지 중 하나 — 다른 검증(bboxPx 구조 등)과 달리 className 하나만으로 박스 전체를
        // 버리기보다는, 나머지 유효한 정보(위치·크기)는 보존하는 쪽을 선택했다).
        String className = (box.className() == null || box.className().isBlank()) ? "unknown" : box.className();
        return new ValidatedBox(className, confidence, x, y, width, height);
    }

    private void validateDistance(AiCargoAnalysisMessage.Distance distance) {
        if (distance == null) {
            return;
        }
        Double valueCm = distance.valueCm();
        if (valueCm != null) {
            if (!isFinite(valueCm)) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DISTANCE_INVALID, "valueCm는 NaN/Infinity를 허용하지 않습니다.");
            }
            if (valueCm <= 0) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DISTANCE_INVALID, "valueCm는 0보다 커야 합니다.");
            }
        }
        Double stdCm = distance.stdCm();
        if (stdCm != null) {
            if (!isFinite(stdCm)) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DISTANCE_INVALID, "stdCm는 NaN/Infinity를 허용하지 않습니다.");
            }
            if (stdCm < 0) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DISTANCE_INVALID, "stdCm는 0 이상이어야 합니다.");
            }
        }
    }

    private void validateDimensions(AiCargoAnalysisMessage.Dimensions dimensions) {
        if (dimensions == null) {
            return;
        }
        Double width = dimensions.widthCm();
        if (width != null) {
            if (!isFinite(width)) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID, "widthCm는 NaN/Infinity를 허용하지 않습니다.");
            }
            if (width <= 0) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID, "widthCm는 0보다 커야 합니다.");
            }
        }
        Double height = dimensions.heightCm();
        if (height != null) {
            if (!isFinite(height)) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID, "heightCm는 NaN/Infinity를 허용하지 않습니다.");
            }
            if (height <= 0) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID, "heightCm는 0보다 커야 합니다.");
            }
        }
        // depthCm/volumeCm3은 정면 카메라만으로 측정 불가할 수 있어 항상 null을 허용한다(prompt26.md
        // 2.4장) — 값이 있을 때 NaN/Infinity만 방어한다. 백엔드는 depth로부터 volume을 계산하지 않는다.
        if (dimensions.depthCm() != null && !isFinite(dimensions.depthCm())) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID, "depthCm는 NaN/Infinity를 허용하지 않습니다.");
        }
        if (dimensions.volumeCm3() != null && !isFinite(dimensions.volumeCm3())) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID, "volumeCm3은 NaN/Infinity를 허용하지 않습니다.");
        }
        if (dimensions.scale() != null && DimensionScale.fromRaw(dimensions.scale()).isEmpty()) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_DIMENSIONS_INVALID,
                    "scale은 REAL 또는 MINIATURE여야 합니다: " + dimensions.scale());
        }
    }

    /**
     * direction 배열의 enum 매핑, 중복, 반대 방향(left↔right, front↔back) 동시 조합을 검증한다
     * (prompt26.md 2.5장). message는 검증하지 않고 그대로 저장한다 — 화면 표시용 문구를 백엔드가 다시
     * 만들지 않는다는 원칙을 따른다.
     */
    private List<LoadBalanceDirection> validateLoadBalance(AiCargoAnalysisMessage.LoadBalance loadBalance) {
        if (loadBalance == null || loadBalance.direction() == null) {
            return List.of();
        }
        List<String> raw = loadBalance.direction();
        if (raw.size() > MAX_LOAD_DIRECTIONS) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_LOAD_BALANCE_INVALID,
                    "direction은 최대 " + MAX_LOAD_DIRECTIONS + "개까지 허용됩니다.");
        }
        List<LoadBalanceDirection> parsed = new ArrayList<>();
        for (String value : raw) {
            LoadBalanceDirection direction = LoadBalanceDirection.fromRaw(value)
                    .orElseThrow(() -> new BusinessException(ErrorCode.AI_ANALYSIS_LOAD_BALANCE_INVALID,
                            "알 수 없는 direction 값입니다: " + value));
            if (parsed.contains(direction)) {
                throw new BusinessException(ErrorCode.AI_ANALYSIS_LOAD_BALANCE_INVALID,
                        "direction에 중복된 값이 있습니다: " + value);
            }
            for (LoadBalanceDirection existing : parsed) {
                if (existing.opposite() == direction) {
                    throw new BusinessException(ErrorCode.AI_ANALYSIS_LOAD_BALANCE_INVALID,
                            "서로 반대 방향은 함께 사용할 수 없습니다: " + existing.rawValue() + ", " + direction.rawValue());
                }
            }
            parsed.add(direction);
        }
        return parsed;
    }

    /**
     * NaN/Infinity만 방어하고 부호·범위는 그대로 둔다 — 부호가 곧 방향을 의미하므로 절댓값으로 저장하지
     * 않으며, -1~1 범위 검증처럼 근거 없는 임의 범위 제한도 추가하지 않는다(prompt26.md 2.6장).
     */
    private void validateRatios(AiCargoAnalysisMessage.Ratios ratios) {
        if (ratios == null) {
            return;
        }
        if (ratios.horizontal() != null && !isFinite(ratios.horizontal())) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_RATIO_INVALID, "horizontal은 NaN/Infinity를 허용하지 않습니다.");
        }
        if (ratios.vertical() != null && !isFinite(ratios.vertical())) {
            throw new BusinessException(ErrorCode.AI_ANALYSIS_RATIO_INVALID, "vertical은 NaN/Infinity를 허용하지 않습니다.");
        }
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
    }

    private void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, message);
        }
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private AiCargoAnalysis toEntity(AiCargoAnalysisMessage message, AiAnalysisStatus status,
            List<LoadBalanceDirection> directions, LocalDateTime receivedAt) {
        AiCargoAnalysis entity = new AiCargoAnalysis();
        entity.setAnalysisId(message.analysisId());
        entity.setSchemaVersion(message.schemaVersion());
        entity.setVehicleId(message.vehicleId());
        entity.setCargoId(message.cargoId());
        entity.setStatus(status);
        if (message.distance() != null) {
            entity.setDistanceCm(message.distance().valueCm());
            entity.setDistanceStdCm(message.distance().stdCm());
        }
        if (message.dimensions() != null) {
            entity.setWidthCm(message.dimensions().widthCm());
            entity.setHeightCm(message.dimensions().heightCm());
            entity.setDepthCm(message.dimensions().depthCm());
            entity.setVolumeCm3(message.dimensions().volumeCm3());
            DimensionScale.fromRaw(message.dimensions().scale()).ifPresent(entity::setDimensionScale);
        }
        entity.setLoadDirection(joinDirections(directions));
        if (message.loadBalance() != null) {
            entity.setLoadMessage(message.loadBalance().message());
        }
        if (message.ratios() != null) {
            entity.setRatioHorizontal(message.ratios().horizontal());
            entity.setRatioVertical(message.ratios().vertical());
        }
        entity.setMessage(message.message());
        entity.setCapturedAt(message.capturedAt());
        entity.setProcessedAt(message.processedAt());
        entity.setReceivedAt(receivedAt);
        entity.setCreatedAt(receivedAt);
        return entity;
    }

    private String joinDirections(List<LoadBalanceDirection> directions) {
        if (directions.isEmpty()) {
            return null;
        }
        return directions.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private List<LoadBalanceDirection> splitDirections(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(LoadBalanceDirection::valueOf)
                .collect(Collectors.toList());
    }

    private AiCargoAnalysisResponse toResponse(AiCargoAnalysis analysis, List<AiCargoDetectionBox> boxes) {
        List<AiCargoAnalysisResponse.DetectedBox> detectedBoxes = boxes.stream()
                .map(b -> new AiCargoAnalysisResponse.DetectedBox(
                        b.getClassName(), b.getConfidence(),
                        List.of(b.getBboxX(), b.getBboxY(), b.getBboxWidth(), b.getBboxHeight())))
                .collect(Collectors.toList());
        AiCargoAnalysisResponse.Detection detection = new AiCargoAnalysisResponse.Detection(detectedBoxes);

        AiCargoAnalysisResponse.Distance distance =
                (analysis.getDistanceCm() != null || analysis.getDistanceStdCm() != null)
                        ? new AiCargoAnalysisResponse.Distance(analysis.getDistanceCm(), analysis.getDistanceStdCm())
                        : null;

        AiCargoAnalysisResponse.Dimensions dimensions =
                (analysis.getWidthCm() != null || analysis.getHeightCm() != null
                        || analysis.getDepthCm() != null || analysis.getVolumeCm3() != null)
                        ? new AiCargoAnalysisResponse.Dimensions(analysis.getWidthCm(), analysis.getHeightCm(),
                                analysis.getDepthCm(), analysis.getVolumeCm3(), analysis.getDimensionScale())
                        : null;

        List<LoadBalanceDirection> directions = splitDirections(analysis.getLoadDirection());
        AiCargoAnalysisResponse.LoadBalance loadBalance =
                (!directions.isEmpty() || analysis.getLoadMessage() != null)
                        ? new AiCargoAnalysisResponse.LoadBalance(directions, analysis.getLoadMessage())
                        : null;

        AiCargoAnalysisResponse.Ratios ratios =
                (analysis.getRatioHorizontal() != null || analysis.getRatioVertical() != null)
                        ? new AiCargoAnalysisResponse.Ratios(analysis.getRatioHorizontal(), analysis.getRatioVertical())
                        : null;

        return new AiCargoAnalysisResponse(
                analysis.getAnalysisId(),
                analysis.getVehicleId(),
                analysis.getCargoId(),
                analysis.getStatus(),
                detection,
                distance,
                dimensions,
                loadBalance,
                ratios,
                analysis.getMessage(),
                analysis.getCapturedAt(),
                analysis.getProcessedAt(),
                analysis.getReceivedAt());
    }

    private record ValidatedBox(String className, Double confidence, int x, int y, int width, int height) {
    }
}
