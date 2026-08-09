package com.fast.backend.traffic.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * yaw 정규화 검증.
 *
 * <p>규격은 yaw 를 −π~+π 로 정의한다. 순환로 C2 모서리(왼쪽 통로를 남으로)가 270° 라
 * 그대로 변환하면 +4.712 가 되어 범위를 벗어난다 — 이 테스트가 그 회귀를 막는다.
 */
class AnglesTest {

    private static final double TOL = 1e-9;

    @Test
    @DisplayName("규격의 네 모서리 yaw 와 일치한다")
    void cornerYaws() {
        assertThat(Angles.toNormalizedRadians(90)).isCloseTo(Math.PI / 2, within(TOL));    // C0 북
        assertThat(Angles.toNormalizedRadians(180)).isCloseTo(Math.PI, within(TOL));       // C1 서
        assertThat(Angles.toNormalizedRadians(270)).isCloseTo(-Math.PI / 2, within(TOL));  // C2 남
        assertThat(Angles.toNormalizedRadians(0)).isCloseTo(0.0, within(TOL));             // C3 동
    }

    @Test
    @DisplayName("범위 밖 라디안을 접는다")
    void wraps() {
        assertThat(Angles.normalizeRadians(3 * Math.PI / 2)).isCloseTo(-Math.PI / 2, within(TOL));
        assertThat(Angles.normalizeRadians(2 * Math.PI)).isCloseTo(0.0, within(TOL));
        assertThat(Angles.normalizeRadians(-3 * Math.PI / 2)).isCloseTo(Math.PI / 2, within(TOL));
        assertThat(Angles.normalizeRadians(5 * Math.PI)).isCloseTo(Math.PI, within(TOL));
    }

    /** 규격 예시가 +3.1416(서쪽)이라 −π 로 바꾸면 문서와 눈으로 대조할 때 달라 보인다. */
    @Test
    @DisplayName("+π 는 그대로 둔다")
    void keepsPositivePi() {
        assertThat(Angles.normalizeRadians(Math.PI)).isCloseTo(Math.PI, within(TOL));
    }

    @Test
    @DisplayName("범위 안 값은 그대로")
    void passthrough() {
        for (double v : new double[] {0, 0.5, -0.5, 1.5708, -1.5708, 3.0, -3.0}) {
            assertThat(Angles.normalizeRadians(v)).isCloseTo(v, within(TOL));
        }
    }

    /** 0 으로 바꾸면 잘못된 방향을 "정상"으로 만들어 원인을 숨긴다. */
    @Test
    @DisplayName("NaN·무한대는 그대로 돌려준다")
    void nonFinite() {
        assertThat(Angles.normalizeRadians(Double.NaN)).isNaN();
        assertThat(Angles.normalizeRadians(Double.POSITIVE_INFINITY))
                .isEqualTo(Double.POSITIVE_INFINITY);
    }
}
