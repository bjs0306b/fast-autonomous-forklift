package com.fast.backend.station.adapter;

import com.fast.backend.station.domain.StationDirection;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementBox;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link StationMeasurementMessage}(수신 DTO)를 내부 저장 모델({@link StationMeasurement} +
 * {@link StationMeasurementBox})로, 그리고 저장 모델을 응답 DTO({@link StationMeasurementResponse})로
 * 변환한다(prompt16.md 5단계). 기존 {@code AiCargoAnalysisMessage}로 강제 역직렬화하지 않고 스테이션
 * 전용 변환만 담당한다(원칙 2·3·5번).
 *
 * <p><b>OffsetDateTime 보존</b>: {@code measured_at}의 오프셋을 잃지 않도록 UTC 변환 시각과 오프셋 분을
 * 분리 저장하고({@link #toUtc}/{@link #offsetMinutes}), 응답 시 두 값으로 원래 {@link OffsetDateTime}을
 * 복원한다({@link #reconstructOffset}). MySQL/H2 공용 DATETIME 컬럼과 호환되는 방식이다(prompt16.md 6단계).
 */
@Component
public class StationMeasurementAdapter {

    /** payload 배열 순서를 그대로 유지하기 위해 정규화된 detection.boxes 리스트(null이면 빈 리스트). */
    public List<StationMeasurementMessage.DetectedBox> normalizedBoxes(StationMeasurementMessage message) {
        if (message.detection() == null || message.detection().boxes() == null) {
            return List.of();
        }
        return message.detection().boxes();
    }

    public StationMeasurement toEntity(StationMeasurementMessage message, StationMeasurementStatus status,
            List<StationDirection> directions, LocalDateTime receivedAt) {
        StationMeasurement entity = new StationMeasurement();
        entity.setMeasurementId(message.measurementId());
        entity.setStationId(message.stationId());
        entity.setSchemaVersion(message.schemaVersion());
        entity.setMeasuredAtUtc(toUtc(message.measuredAt()));
        entity.setMeasuredAtOffsetMinutes(offsetMinutes(message.measuredAt()));
        entity.setStatus(status);

        StationMeasurementMessage.Detection detection = message.detection();
        if (detection != null) {
            entity.setBoxCount(normalizedBoxes(message).size());
            StationMeasurementMessage.PalletDetection pallet = detection.pallet();
            if (pallet != null) {
                List<Integer> bbox = pallet.bboxPx();
                if (bbox != null) {
                    entity.setPalletBboxX(bbox.get(0));
                    entity.setPalletBboxY(bbox.get(1));
                    entity.setPalletBboxWidth(bbox.get(2));
                    entity.setPalletBboxHeight(bbox.get(3));
                }
                entity.setPalletScore(pallet.score());
            }
        }

        StationMeasurementMessage.Distance distance = message.distance();
        if (distance != null) {
            entity.setFrontCm(distance.frontCm());
            entity.setDistanceStdCm(distance.stdCm());
            entity.setFramesUsed(distance.framesUsed());
        }

        // dimensions/loadBalance는 status != ok이면 애초에 null(검증에서 강제) → 컬럼도 null 저장
        StationMeasurementMessage.Dimensions dimensions = message.dimensions();
        if (dimensions != null) {
            entity.setHeightCm(dimensions.heightCm());
            entity.setWidthCm(dimensions.widthCm());
            entity.setDepthCm(dimensions.depthCm()); // 정책상 항상 null
            entity.setMiniatureScale(dimensions.miniatureScale());
            entity.setMiniatureHeightMm(dimensions.miniatureHeightMm());
            entity.setMiniatureWidthMm(dimensions.miniatureWidthMm());
        }

        StationMeasurementMessage.LoadBalance loadBalance = message.loadBalance();
        if (loadBalance != null) {
            entity.setEccentric(loadBalance.eccentric());
            entity.setLoadDirection(joinDirections(directions));
            entity.setRatioX(loadBalance.ratioX());
            entity.setRatioY(loadBalance.ratioY());
            entity.setMagnitude(loadBalance.magnitude());
            entity.setThreshold(loadBalance.threshold());
            entity.setLoadMessage(loadBalance.message());
        }

        entity.setReceivedAt(receivedAt);
        entity.setCreatedAt(receivedAt);
        return entity;
    }

    /** detection.boxes를 자식 엔티티로 변환한다. 부모 id는 부모 insert 이후 호출부가 채운다. */
    public List<StationMeasurementBox> toBoxes(StationMeasurementMessage message, LocalDateTime receivedAt) {
        List<StationMeasurementMessage.DetectedBox> source = normalizedBoxes(message);
        List<StationMeasurementBox> boxes = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            StationMeasurementMessage.DetectedBox src = source.get(i);
            StationMeasurementBox box = new StationMeasurementBox();
            box.setBoxOrder(i);
            List<Integer> bbox = src.bboxPx();
            if (bbox != null) {
                box.setBboxX(bbox.get(0));
                box.setBboxY(bbox.get(1));
                box.setBboxWidth(bbox.get(2));
                box.setBboxHeight(bbox.get(3));
            }
            box.setScore(src.score());
            box.setCreatedAt(receivedAt);
            boxes.add(box);
        }
        return boxes;
    }

    public StationMeasurementResponse toResponse(StationMeasurement m, List<StationMeasurementBox> boxes) {
        List<StationMeasurementResponse.DetectedBox> responseBoxes = boxes.stream()
                .map(b -> new StationMeasurementResponse.DetectedBox(bboxOf(b), b.getScore()))
                .collect(Collectors.toList());

        StationMeasurementResponse.Pallet pallet = null;
        if (m.getPalletBboxX() != null || m.getPalletScore() != null) {
            List<Integer> palletBbox = (m.getPalletBboxX() != null)
                    ? List.of(m.getPalletBboxX(), m.getPalletBboxY(), m.getPalletBboxWidth(), m.getPalletBboxHeight())
                    : null;
            pallet = new StationMeasurementResponse.Pallet(palletBbox, m.getPalletScore());
        }
        StationMeasurementResponse.Detection detection =
                new StationMeasurementResponse.Detection(m.getBoxCount(), responseBoxes, pallet);

        StationMeasurementResponse.Distance distance =
                (m.getFrontCm() != null || m.getDistanceStdCm() != null || m.getFramesUsed() != null)
                        ? new StationMeasurementResponse.Distance(m.getFrontCm(), m.getDistanceStdCm(), m.getFramesUsed())
                        : null;

        StationMeasurementResponse.Dimensions dimensions =
                (m.getHeightCm() != null || m.getWidthCm() != null || m.getMiniatureScale() != null)
                        ? new StationMeasurementResponse.Dimensions(m.getHeightCm(), m.getWidthCm(), m.getDepthCm(),
                                m.getMiniatureScale(), m.getMiniatureHeightMm(), m.getMiniatureWidthMm())
                        : null;

        StationMeasurementResponse.LoadBalance loadBalance =
                (m.getEccentric() != null || m.getLoadDirection() != null || m.getRatioX() != null
                        || m.getMagnitude() != null || m.getLoadMessage() != null)
                        ? new StationMeasurementResponse.LoadBalance(m.getEccentric(),
                                splitDirections(m.getLoadDirection()), m.getRatioX(), m.getRatioY(),
                                m.getMagnitude(), m.getThreshold(), m.getLoadMessage())
                        : null;

        return new StationMeasurementResponse(
                m.getMeasurementId(),
                m.getStationId(),
                m.getSchemaVersion(),
                reconstructOffset(m.getMeasuredAtUtc(), m.getMeasuredAtOffsetMinutes()),
                m.getStatus(),
                detection,
                distance,
                dimensions,
                loadBalance,
                m.getReceivedAt());
    }

    private List<Integer> bboxOf(StationMeasurementBox b) {
        if (b.getBboxX() == null) {
            return null;
        }
        return List.of(b.getBboxX(), b.getBboxY(), b.getBboxWidth(), b.getBboxHeight());
    }

    // ── OffsetDateTime 보존 헬퍼 ─────────────────────────────────────────────

    static LocalDateTime toUtc(OffsetDateTime measuredAt) {
        if (measuredAt == null) {
            return null;
        }
        return measuredAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    static Integer offsetMinutes(OffsetDateTime measuredAt) {
        if (measuredAt == null) {
            return null;
        }
        return measuredAt.getOffset().getTotalSeconds() / 60;
    }

    static OffsetDateTime reconstructOffset(LocalDateTime utc, Integer offsetMinutes) {
        if (utc == null) {
            return null;
        }
        int minutes = (offsetMinutes != null) ? offsetMinutes : 0;
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(minutes * 60);
        return utc.atOffset(ZoneOffset.UTC).withOffsetSameInstant(offset);
    }

    private String joinDirections(List<StationDirection> directions) {
        if (directions == null || directions.isEmpty()) {
            return null;
        }
        return directions.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private List<StationDirection> splitDirections(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(StationDirection::valueOf)
                .collect(Collectors.toList());
    }
}
