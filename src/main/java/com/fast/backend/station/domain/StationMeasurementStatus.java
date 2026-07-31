package com.fast.backend.station.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 측정 스테이션 측정 결과 상태(prompt16.md 3단계, prompt95.md 4장). JSON 값은 소문자이며
 * {@link #rawValue()}(@JsonValue)로 직렬화하고 {@link #fromRaw(String)}로 역직렬화·검증한다 —
 * 기존 {@code AiAnalysisStatus}와 동일한 패턴이되 스테이션 도메인 전용으로 분리했다(원칙 3·5번).
 *
 * <p><b>{@link #DIMENSIONS_ONLY}</b>(prompt95.md 4장 추가): 파렛트를 검출하지 못해 <b>치수 측정만
 * 성공</b>한 상태다. 화물 높이는 있지만 전복·돌출 판정을 할 수 없다 — {@code tippingLevel}과
 * {@code overhangRatio}는 <b>반드시 null</b>이다. 백엔드가 없는 판정을 지어내지 않는다.
 * 높이 기반 적재 판단은 여전히 가능하다(팔레트 높이는 설정값을 더한다).
 * DB CHECK 제약({@code schema.sql})은 이 값을 이미 허용하고 있었고, enum 쪽에만 빠져 있었다.
 */
public enum StationMeasurementStatus {

    OK("ok"),
    DIMENSIONS_ONLY("dimensions_only"),
    NO_DETECTION("no_detection"),
    UNRELIABLE("unreliable");

    private final String rawValue;

    StationMeasurementStatus(String rawValue) {
        this.rawValue = rawValue;
    }

    @JsonValue
    public String rawValue() {
        return rawValue;
    }

    public static Optional<StationMeasurementStatus> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase();
        for (StationMeasurementStatus status : values()) {
            if (status.rawValue.equals(normalized)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
