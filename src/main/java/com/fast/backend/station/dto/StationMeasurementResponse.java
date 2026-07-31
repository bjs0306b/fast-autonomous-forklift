package com.fast.backend.station.dto;

import com.fast.backend.station.domain.StationDirection;
import com.fast.backend.station.domain.StationMeasurementStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 측정 스테이션 측정 결과 조회/브로드캐스트 응답 DTO(prompt16.md 9·10단계, prompt96). 응답 JSON은
 * camelCase 다. {@code measuredAt}은 복원된 {@link OffsetDateTime}이라 오프셋({@code +09:00})이
 * 그대로 응답된다.
 *
 * <p><b>두 세대의 필드가 함께 있다.</b> {@code detection}/{@code distance}/{@code dimensions}/
 * {@code loadBalance}/{@code stationId}/{@code schemaVersion}은 MQTT 시절 규격이라 REST 로 저장된
 * 행에서는 전부 {@code null}이다. REST 로 들어온 결과는 아래 세션·안전 필드
 * ({@code sessionId}/{@code cargoId}/{@code cargoHeight}/{@code tippingLevel}/{@code overhangRatio})가
 * 채워진다. 소비자는 {@code sessionId} 유무로 두 세대를 구분할 수 있다.
 */
public record StationMeasurementResponse(
        String measurementId,
        String stationId,
        String schemaVersion,
        OffsetDateTime measuredAt,
        StationMeasurementStatus status,
        Detection detection,
        Distance distance,
        Dimensions dimensions,
        LoadBalance loadBalance,
        LocalDateTime receivedAt,

        // ── 측정 세션·REST 계약(prompt96) ─────────────────────────────────────
        /** 이 측정이 속한 세션. MQTT 시절 행이면 null. */
        String sessionId,
        /** 세션이 가리키는 화물. 세션에서 가져온다(측정 행에는 없다). */
        String cargoId,
        /** 팔레트를 제외한 화물 높이(meter). {@code dimensions.heightCm}(cm)와 단위가 다르다. */
        Double cargoHeight,
        /** 전복 위험 등급(SAFE/WARNING/DANGER). 판정 불가 상태면 null. */
        String tippingLevel,
        /** 팔레트 기준 화물 돌출 비율(무차원). 판정 불가 상태면 null. */
        Double overhangRatio,
        /** 이 측정으로 적재 추천을 실행할 수 있는지(prompt96 안전 게이트 결과). */
        boolean placementEligible
) {

    public record Detection(Integer boxCount, List<DetectedBox> boxes, Pallet pallet) {
    }

    public record DetectedBox(List<Integer> bboxPx, Double score) {
    }

    public record Pallet(List<Integer> bboxPx, Double score) {
    }

    public record Distance(Double frontCm, Double stdCm, Integer framesUsed) {
    }

    public record Dimensions(
            Double heightCm, Double widthCm, Double depthCm,
            Integer miniatureScale, Double miniatureHeightMm, Double miniatureWidthMm) {
    }

    public record LoadBalance(
            Boolean eccentric, List<StationDirection> direction,
            Double ratioX, Double ratioY, Double magnitude, Double threshold, String message) {
    }
}
