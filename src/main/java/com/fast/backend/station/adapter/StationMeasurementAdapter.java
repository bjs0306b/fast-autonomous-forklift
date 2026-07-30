package com.fast.backend.station.adapter;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.domain.StationMeasurementStatus;
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MQTT 수신 DTO ↔ 도메인 ↔ 응답 DTO 변환(FR-202 세션 기반 구조, prompt85).
 *
 * <p>변환에서 <b>버려지는 값</b>이 많다는 점이 이 클래스의 핵심이다. 스테이션이 보내는 원본
 * (detection/bbox/distance/miniature/loadBalance 상세)에는 저장할 컬럼이 없다. 무엇을 남기는지
 * 한곳에서 보이도록 여기 모아 두고, 버리는 값은 주석으로 명시한다 — 나중에 "왜 안 남았지"를
 * 코드에서 바로 확인할 수 있어야 한다.
 */
@Component
public class StationMeasurementAdapter {

    /**
     * 수신 메시지를 저장 엔티티로 바꾼다.
     *
     * <p>남기는 값: measurementId / sessionId / status / cargoHeight(= dimensions.heightCm).
     * <p>버리는 값: stationId, schemaVersion, measuredAt, detection 전체, distance 전체,
     * dimensions 의 width/depth/miniature, loadBalance 전체.
     * <p>{@code tippingLevel}/{@code overhangRatio} 는 <b>스테이션이 아직 보내지 않는다</b> —
     * 백엔드가 임의로 계산해 채우지 않고 null 로 둔다(없는 판정을 지어내지 않는다).
     */
    public StationMeasurement toEntity(StationMeasurementMessage message, StationMeasurementStatus status,
            String sessionId, LocalDateTime createdAt) {
        StationMeasurement entity = new StationMeasurement();
        entity.setMeasurementId(message.measurementId());
        entity.setSessionId(sessionId);
        entity.setStatus(status);
        entity.setCargoHeight(message.dimensions() == null ? null : message.dimensions().heightCm());
        entity.setTippingLevel(null);
        entity.setOverhangRatio(null);
        entity.setCreatedAt(createdAt);
        return entity;
    }

    /** 저장된 값만 응답으로 내보낸다. {@code cargoId} 는 세션에서 온다. */
    public StationMeasurementResponse toResponse(StationMeasurement entity, String cargoId) {
        return new StationMeasurementResponse(
                entity.getSequenceNo(),
                entity.getMeasurementId(),
                entity.getSessionId(),
                cargoId,
                entity.getStatus(),
                entity.getCargoHeight(),
                entity.getTippingLevel(),
                entity.getOverhangRatio(),
                entity.getCreatedAt());
    }
}
