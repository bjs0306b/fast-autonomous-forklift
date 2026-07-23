package com.fast.backend.ai.domain;

import java.util.Optional;

/**
 * dimensions.scale 값(prompt26.md 2.4장). {@code REAL}은 실측값, {@code MINIATURE}는 실물값÷10 축소
 * 모형 값이라는 의미만 나타내는 메타데이터다 — 이 프로젝트는 "방식 B"(AI가 scale에 맞는 완성값을 이미
 * 계산해서 보낸다)를 선택했으므로, 백엔드는 이 값을 보고 스스로 단위를 환산하지 않고 그대로 저장·응답한다
 * (선택 이유는 {@code AiCargoAnalysisService} Javadoc과 answer26.md 참고).
 */
public enum DimensionScale {
    REAL,
    MINIATURE;

    public static Optional<DimensionScale> fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(DimensionScale.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
