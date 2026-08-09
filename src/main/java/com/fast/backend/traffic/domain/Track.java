package com.fast.backend.traffic.domain;

import java.util.List;

/**
 * 순환로 기하 — 차량 위치를 <b>경로를 따라간 거리(호장 s)</b> 로 바꾼다.
 *
 * <p><b>왜 유클리드 거리로는 안 되는가.</b> 창고 가운데가 랙이라 통로가 좌우로 갈라져 있다.
 * 왼쪽 통로(x=5)와 오른쪽 통로(x=15.5)는 직선으로 재면 10.5 지만, 차량이 실제로 가야 하는
 * 거리는 랙을 돌아가는 <b>반 바퀴(약 33)</b> 다. 직선 거리로 앞뒤를 판정하면 반대편 통로의
 * 차량을 "바로 앞차"로 착각해 엉뚱한 차를 세운다.
 *
 * <p>그래서 (x, y) 를 순환로 폴리라인 위로 투영해 스칼라 하나(s)로 만들고,
 * {@link #gap(double, double)} 로 <b>진행 방향 기준</b> 앞뒤를 가린다.
 *
 * <pre>
 *   y=27   (5,27) ◀────── 위 홀 ────── (15.5,27)
 *             │                            ▲
 *             │ 왼쪽 통로       [랙]        │ 오른쪽 통로
 *             ▼                            │
 *   y=4    (5,4) ─────── 아래 홀 ──────▶ (15.5,4)
 * </pre>
 *
 * <p>참조 구현은 {@code isaac_sim/nav2/scripts/track.py} 이고 이 클래스는 그 번역이다.
 * 계산 결과가 갈라지면 시뮬(F팀)과 관제(백엔드)가 서로 다른 앞차를 보게 되므로,
 * <b>파이썬 쪽을 고칠 때 이 클래스도 같이 고쳐야 한다.</b>
 *
 * <p>스프링·DB·MQTT 를 전혀 모르는 순수 계산 클래스다. 불변이라 여러 tick 이 공유해도 된다.
 */
public final class Track {

    /** 모서리 좌표(진행 순서대로). 닫힌 경로라 마지막 → 처음으로 이어진다. */
    private final double[][] corners;
    /** 각 변의 길이. {@code segLength[i]} 는 corners[i] → corners[i+1]. */
    private final double[] segLength;
    /** 각 모서리의 s(시작점부터 그 모서리까지 따라간 거리). */
    private final double[] cornerS;
    /** 전체 둘레. */
    private final double length;

    /** 길이가 0 인 변은 투영에서 건너뛴다(0 나눗셈 방지). */
    private static final double DEGENERATE_SEGMENT = 1e-6;

    /**
     * @param corners 진행 방향 순서의 모서리 목록. 최소 2개, 각 원소는 {@code [x, y]}
     * @throws IllegalArgumentException 모서리가 부족하거나 둘레가 0 인 경우 —
     *         그 상태로 두면 {@link #gap} 이 0 나눗셈을 내고, 관제 전체가 조용히 멈춘다
     */
    public Track(List<double[]> corners) {
        if (corners == null || corners.size() < 2) {
            throw new IllegalArgumentException("순환로 모서리는 2개 이상이어야 합니다: " + corners);
        }
        int n = corners.size();
        this.corners = new double[n][];
        this.segLength = new double[n];
        this.cornerS = new double[n];

        double acc = 0.0;
        for (int i = 0; i < n; i++) {
            double[] point = corners.get(i);
            if (point == null || point.length != 2
                    || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) {
                throw new IllegalArgumentException(
                        "순환로 모서리는 유한한 [x, y] 여야 합니다: index=" + i);
            }
            this.corners[i] = new double[] {point[0], point[1]};
        }
        for (int i = 0; i < n; i++) {
            double[] a = this.corners[i];
            double[] b = this.corners[(i + 1) % n];
            cornerS[i] = acc;
            segLength[i] = Math.hypot(b[0] - a[0], b[1] - a[1]);
            acc += segLength[i];
        }
        if (acc <= DEGENERATE_SEGMENT) {
            throw new IllegalArgumentException("순환로 둘레가 0 입니다: " + acc);
        }
        this.length = acc;
    }

    /** 둘레(m). */
    public double length() {
        return length;
    }

    /** 모서리 개수. */
    public int cornerCount() {
        return corners.length;
    }

    /**
     * (x, y) 를 경로에 투영한다.
     *
     * @return {@link Projection} — 따라간 거리 s 와 경로에서 벗어난 거리
     */
    public Projection project(double x, double y) {
        double bestOffset = Double.MAX_VALUE;
        double bestS = 0.0;

        for (int i = 0; i < corners.length; i++) {
            double len = segLength[i];
            if (len < DEGENERATE_SEGMENT) {
                continue;
            }
            double[] a = corners[i];
            double[] b = corners[(i + 1) % corners.length];
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];

            // 변 위로의 정사영 비율. 변 밖으로 나가면 끝점으로 잘라낸다(선분이지 직선이 아니다).
            double t = ((x - a[0]) * dx + (y - a[1]) * dy) / (len * len);
            t = Math.max(0.0, Math.min(1.0, t));

            double px = a[0] + t * dx;
            double py = a[1] + t * dy;
            double offset = Math.hypot(x - px, y - py);
            if (offset < bestOffset) {
                bestOffset = offset;
                bestS = cornerS[i] + t * len;
            }
        }
        return new Projection(bestS, bestOffset);
    }

    /**
     * {@code from} 에서 <b>진행 방향으로</b> {@code to} 까지 남은 거리. 항상 0 이상 둘레 미만.
     *
     * <p><b>뺄셈 방향이 핵심이다.</b> {@code gap(내 s, 상대 s)} 가 작다는 것은
     * "상대가 내 바로 앞에 있다"는 뜻이다. 반대로 쓰면 뒤차를 앞차로 착각해 엉뚱한 차를 세운다
     * (F팀 규격 §10 "흔한 함정" 2번).
     */
    public double gap(double from, double to) {
        double g = (to - from) % length;
        return g < 0 ? g + length : g;
    }

    /** 거리 s 지점의 좌표와 그 지점의 진행 방향(degree, 0=+X, 반시계). */
    public Pose poseAt(double s) {
        double target = normalize(s);
        double acc = 0.0;
        for (int i = 0; i < corners.length; i++) {
            double len = segLength[i];
            if (len < DEGENERATE_SEGMENT) {
                continue;
            }
            if (target <= acc + len) {
                double[] a = corners[i];
                double[] b = corners[(i + 1) % corners.length];
                double t = (target - acc) / len;
                return new Pose(
                        a[0] + t * (b[0] - a[0]),
                        a[1] + t * (b[1] - a[1]),
                        headingDeg(a, b));
            }
            acc += len;
        }
        // 부동소수 오차로 마지막 변을 넘긴 경우. 마지막 모서리로 떨어뜨린다.
        int last = corners.length - 1;
        double[] a = corners[last];
        double[] b = corners[0];
        return new Pose(b[0], b[1], headingDeg(a, b));
    }

    /**
     * 진행 방향으로 다음에 지날 모서리 번호.
     *
     * <p>{@code tol} 안에 있는 모서리는 "이미 지났다"고 보고 건너뛴다 — 안 그러면 모서리에
     * 도착한 순간 같은 모서리를 목표로 계속 다시 주므로 차량이 그 자리에 붙는다.
     */
    public int nextCornerIndex(double s, double tol) {
        int best = 0;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < corners.length; i++) {
            double g = gap(s, cornerS[i]);
            double weighted = g > tol ? g : g + length;   // 너무 가까우면 한 바퀴 뒤로 미룬다
            if (weighted < bestGap) {
                bestGap = weighted;
                best = i;
            }
        }
        return best;
    }

    /** 모서리 {@code index} 의 좌표와, 그 모서리를 돈 뒤 진행할 방향(degree). */
    public Pose cornerPose(int index) {
        int i = Math.floorMod(index, corners.length);
        double[] a = corners[i];
        double[] b = corners[(i + 1) % corners.length];
        return new Pose(a[0], a[1], headingDeg(a, b));
    }

    /** s 를 [0, length) 로 접는다. */
    public double normalize(double s) {
        double v = s % length;
        return v < 0 ? v + length : v;
    }

    private static double headingDeg(double[] a, double[] b) {
        double deg = Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
        return deg < 0 ? deg + 360.0 : deg;
    }

    /**
     * 투영 결과.
     *
     * @param s      경로를 따라간 거리
     * @param offset 경로에서 벗어난 거리. 이 값이 크면 <b>순환로 위에 있지 않다</b>는 뜻이라
     *               (바이·랙으로 빠진 차량) 차간 판정에서 제외해야 한다
     */
    public record Projection(double s, double offset) {
    }

    /** 경로 위 한 지점. {@code headingDeg} 는 0=+X, 반시계, [0, 360). */
    public record Pose(double x, double y, double headingDeg) {
    }
}
