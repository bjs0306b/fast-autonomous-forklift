package com.fast.backend.station.domain;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Optional;

/**
 * 화물 전복 위험 등급.
 *
 * <p><b>대소문자를 여기서 흡수한다.</b> 측정 AI는 소문자
 * ({@code safe}/{@code warning}/{@code danger})로 판정 결과를 내는데
 * ({@code docs/ai/station-measurement-handoff.md}), DB CHECK 제약은 대문자
 * ({@code 'SAFE','WARNING','DANGER'}, {@code schema.sql})만 허용한다. 소문자를 그대로 INSERT 하면
 * 제약 위반으로 실패하므로, 메시지 입력 경계에서 {@link #fromRaw(String)}로 정규화하고
 * {@link #name()}(대문자)을 저장한다.
 *
 * <p><b>백엔드는 등급을 계산하지 않는다.</b> 판정은 측정 AI가 하고 백엔드는 받은 값을 검증·정규화해
 * 보관만 한다 — 알 수 없는 값이 오면 {@code null}로 바꿔 삼키지 않고 오류로 거부한다(없는 판정을
 * 지어내지 않는다).
 */
public enum TippingLevel {

    SAFE,
    WARNING,
    DANGER;

    /** JSON 직렬화는 DB 저장값과 같은 대문자로 통일한다(응답이 저장값과 달라지지 않게). */
    @JsonValue
    public String rawValue() {
        return name();
    }

    /**
     * 소문자·대문자·혼합 표기를 모두 받아 정규화한다. 알 수 없는 값이거나 blank 면
     * {@link Optional#empty()} — 호출자가 오류로 처리한다. {@code null} 입력은 "값 없음"이므로
     * 마찬가지로 empty 를 돌려주고, null 허용 여부는 status 별 정책이 판단한다.
     */
    public static Optional<TippingLevel> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase();
        for (TippingLevel level : values()) {
            if (level.name().equals(normalized)) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
