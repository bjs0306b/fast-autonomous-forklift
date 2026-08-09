package com.fast.backend.traffic.domain;

/**
 * 각도 정규화.
 *
 * <p><b>왜 필요한가.</b> 규격은 {@code yaw} 를 <b>−π ~ +π</b> 라디안으로 정의한다
 * (F팀 {@code backend-mqtt-guide} §0.2). 그런데 방향을 degree 로 다루다 라디안으로 바꾸면
 * 쉽게 범위를 벗어난다 — 예를 들어 순환로 C2 모서리(왼쪽 통로를 남으로)는 270° 이고,
 * 그대로 변환하면 {@code +4.712} 가 되어 규격의 {@code −1.5708} 과 다른 값이 나간다.
 * 같은 방향이지만 차량이 어떻게 해석할지는 보장되지 않는다.
 *
 * <p>그래서 <b>발행 경계에서 한 번</b> 정규화한다. 계산 중간마다 하면 어디서 했는지
 * 추적하기 어렵고, 안 하면 조용히 범위 밖 값이 나간다.
 */
public final class Angles {

    private static final double TWO_PI = 2 * Math.PI;

    private Angles() {
    }

    /**
     * 라디안을 <b>[−π, +π]</b> 로 접는다.
     *
     * <p>경계값 {@code +π} 는 그대로 둔다 — 규격 예시({@code 3.1416}, 서쪽)가 양수라
     * {@code −π} 로 바꾸면 문서와 눈으로 대조할 때 달라 보인다.
     *
     * @param radians 임의의 라디안. {@code NaN}/{@code Infinity} 는 그대로 돌려준다 —
     *                여기서 0 으로 바꾸면 잘못된 방향을 "정상"으로 만들어 원인을 숨긴다
     */
    public static double normalizeRadians(double radians) {
        if (!Double.isFinite(radians)) {
            return radians;
        }
        double v = radians % TWO_PI;
        if (v > Math.PI) {
            v -= TWO_PI;
        } else if (v < -Math.PI) {
            v += TWO_PI;
        }
        return v;
    }

    /** degree 를 [−π, +π] 라디안으로. */
    public static double toNormalizedRadians(double degrees) {
        return normalizeRadians(Math.toRadians(degrees));
    }
}
