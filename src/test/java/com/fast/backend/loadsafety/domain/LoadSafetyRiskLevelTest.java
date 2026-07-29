package com.fast.backend.loadsafety.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위험 단계 매핑 규칙 검증(prompt63.md 3장). 핵심은 <b>모르는 값을 다른 단계로 흡수하지 않는 것</b>이다 —
 * 미정의 문자열이 NORMAL로 떨어지면 실제로는 위험한 상태가 화면에 "정상"으로 표시된다.
 */
class LoadSafetyRiskLevelTest {

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "CAUTION", "WARNING", "DANGER"})
    void definedLevels_areMappedExactly(String raw) {
        assertThat(LoadSafetyRiskLevel.fromRaw(raw)).isEqualTo(LoadSafetyRiskLevel.valueOf(raw));
    }

    @Test
    void caseAndWhitespace_areAbsorbed() {
        assertThat(LoadSafetyRiskLevel.fromRaw("warning")).isEqualTo(LoadSafetyRiskLevel.WARNING);
        assertThat(LoadSafetyRiskLevel.fromRaw("  Danger  ")).isEqualTo(LoadSafetyRiskLevel.DANGER);
    }

    @Test
    void unknownValues_neverBecomeNormal() {
        // 미정의 값이 조용히 "정상"이 되면 위험 상태를 놓친다. UNKNOWN으로만 떨어져야 한다.
        assertThat(LoadSafetyRiskLevel.fromRaw("CRITICAL")).isEqualTo(LoadSafetyRiskLevel.UNKNOWN);
        assertThat(LoadSafetyRiskLevel.fromRaw("HIGH")).isEqualTo(LoadSafetyRiskLevel.UNKNOWN);
        assertThat(LoadSafetyRiskLevel.fromRaw("")).isEqualTo(LoadSafetyRiskLevel.UNKNOWN);
        assertThat(LoadSafetyRiskLevel.fromRaw(null)).isEqualTo(LoadSafetyRiskLevel.UNKNOWN);
    }

    @Test
    void alerting_isWarningAndDangerOnly() {
        assertThat(LoadSafetyRiskLevel.WARNING.isAlerting()).isTrue();
        assertThat(LoadSafetyRiskLevel.DANGER.isAlerting()).isTrue();
        assertThat(LoadSafetyRiskLevel.NORMAL.isAlerting()).isFalse();
        assertThat(LoadSafetyRiskLevel.CAUTION.isAlerting()).isFalse();
        // UNKNOWN은 경보가 아니다 — 판정 불가를 위험으로 승격하면 오탐이 쏟아진다.
        assertThat(LoadSafetyRiskLevel.UNKNOWN.isAlerting()).isFalse();
    }

    @Test
    void source_unknownValuesAreNotAbsorbed() {
        assertThat(LoadSafetySource.fromRaw("VISION")).isEqualTo(LoadSafetySource.VISION);
        assertThat(LoadSafetySource.fromRaw("sensor")).isEqualTo(LoadSafetySource.SENSOR);
        assertThat(LoadSafetySource.fromRaw("LIDAR")).isEqualTo(LoadSafetySource.UNKNOWN);
        assertThat(LoadSafetySource.fromRaw(null)).isEqualTo(LoadSafetySource.UNKNOWN);
    }
}
