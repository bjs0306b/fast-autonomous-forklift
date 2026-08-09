package com.fast.backend.traffic.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 호장 투영 검증.
 *
 * <p>F팀 규격의 순환로(반시계, 둘레 67.0)를 그대로 쓴다. 값이 어긋나면 시뮬과 관제가
 * 서로 다른 앞차를 보게 되므로, 여기 숫자는 {@code isaac_sim/nav2/scripts/track.py} 와
 * 맞춰야 한다.
 */
class TrackTest {

    /** 반시계: 아래 +x → 오른쪽 +y → 위 -x → 왼쪽 -y */
    private static final Track LOOP = new Track(List.of(
            new double[] {15.5, 4.0},
            new double[] {15.5, 27.0},
            new double[] {5.0, 27.0},
            new double[] {5.0, 4.0}));

    private static final double SIDE_Y = 23.0;    // 세로 변 길이
    private static final double SIDE_X = 10.5;    // 가로 변 길이

    @Test
    @DisplayName("둘레는 세로 2 + 가로 2 = 67.0")
    void length() {
        assertThat(LOOP.length()).isCloseTo(2 * SIDE_Y + 2 * SIDE_X, within(1e-9));
        assertThat(LOOP.length()).isCloseTo(67.0, within(1e-9));
    }

    @Nested
    @DisplayName("project")
    class Project {

        @Test
        @DisplayName("모서리는 누적 거리 그대로 나온다")
        void corners() {
            assertThat(LOOP.project(15.5, 4.0).s()).isCloseTo(0.0, within(1e-9));
            assertThat(LOOP.project(15.5, 27.0).s()).isCloseTo(SIDE_Y, within(1e-9));
            assertThat(LOOP.project(5.0, 27.0).s()).isCloseTo(SIDE_Y + SIDE_X, within(1e-9));
            assertThat(LOOP.project(5.0, 4.0).s()).isCloseTo(2 * SIDE_Y + SIDE_X, within(1e-9));
        }

        @Test
        @DisplayName("변 위의 점은 그 변을 따라간 거리")
        void alongSegment() {
            // 오른쪽 통로 중간
            assertThat(LOOP.project(15.5, 15.5).s()).isCloseTo(11.5, within(1e-9));
        }

        @Test
        @DisplayName("경로에서 떨어진 점은 offset 으로 드러난다")
        void offset() {
            Track.Projection p = LOOP.project(17.5, 15.5);   // 오른쪽으로 2 벗어남
            assertThat(p.offset()).isCloseTo(2.0, within(1e-9));
            assertThat(p.s()).isCloseTo(11.5, within(1e-9));
        }

        @Test
        @DisplayName("경로 위 점의 offset 은 0")
        void onTrack() {
            assertThat(LOOP.project(10.0, 27.0).offset()).isCloseTo(0.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("gap — 이 프로젝트가 가장 자주 틀리는 부분")
    class Gap {

        /**
         * 규격 §2 의 근거를 그대로 검증한다. 두 통로는 직선 10.5 지만 경로상 반 바퀴다.
         * 유클리드로 판정하면 이 두 차량이 "9.0 안"이라 서로 세우게 된다.
         */
        @Test
        @DisplayName("마주 보는 두 통로는 직선 10.5, 경로상 33.5")
        void oppositeAislesAreFarApart() {
            double euclid = Math.hypot(15.5 - 5.0, 15.5 - 15.5);
            assertThat(euclid).isCloseTo(10.5, within(1e-9));

            double right = LOOP.project(15.5, 15.5).s();
            double left = LOOP.project(5.0, 15.5).s();
            assertThat(LOOP.gap(right, left)).isGreaterThan(30.0);
        }

        @Test
        @DisplayName("진행 방향으로 앞에 있으면 작은 값")
        void forwardIsSmall() {
            double me = LOOP.project(15.5, 10.0).s();
            double ahead = LOOP.project(15.5, 14.0).s();     // 반시계라 +y 가 앞
            assertThat(LOOP.gap(me, ahead)).isCloseTo(4.0, within(1e-9));
        }

        /** 뺄셈 방향을 뒤집으면 뒤차를 앞차로 본다 — 규격 §10 함정 2번. */
        @Test
        @DisplayName("뒤에 있으면 거의 한 바퀴")
        void backwardIsAlmostFullLoop() {
            double me = LOOP.project(15.5, 10.0).s();
            double behind = LOOP.project(15.5, 6.0).s();
            assertThat(LOOP.gap(me, behind)).isCloseTo(67.0 - 4.0, within(1e-9));
        }

        @Test
        @DisplayName("자기 자신과의 gap 은 0")
        void self() {
            double s = LOOP.project(15.5, 10.0).s();
            assertThat(LOOP.gap(s, s)).isCloseTo(0.0, within(1e-9));
        }

        @Test
        @DisplayName("항상 0 이상 둘레 미만")
        void range() {
            for (double from = 0; from < 67.0; from += 7.0) {
                for (double to = 0; to < 67.0; to += 5.0) {
                    double g = LOOP.gap(from, to);
                    assertThat(g).isGreaterThanOrEqualTo(0.0).isLessThan(LOOP.length());
                }
            }
        }

        @Test
        @DisplayName("둘레를 넘는 s 도 접어서 처리한다")
        void wraps() {
            assertThat(LOOP.gap(0.0, 67.0 + 5.0)).isCloseTo(5.0, within(1e-9));
            assertThat(LOOP.gap(0.0, -5.0)).isCloseTo(62.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("nextCornerIndex")
    class NextCorner {

        @Test
        @DisplayName("진행 방향 다음 모서리를 고른다")
        void picksForward() {
            double s = LOOP.project(15.5, 10.0).s();          // 오른쪽 통로 중간
            assertThat(LOOP.nextCornerIndex(s, 2.0)).isEqualTo(1);   // (15.5, 27)
        }

        /** 모서리에 다 왔는데 같은 모서리를 또 주면 차량이 그 자리에 붙는다. */
        @Test
        @DisplayName("허용 오차 안의 모서리는 건너뛴다")
        void skipsReachedCorner() {
            double s = LOOP.project(15.5, 26.5).s();          // (15.5,27) 코앞
            assertThat(LOOP.nextCornerIndex(s, 2.0)).isEqualTo(2);   // 다음 모서리로
        }

        @Test
        @DisplayName("마지막 변에서는 처음 모서리로 돌아온다")
        void wrapsToFirst() {
            double s = LOOP.project(5.0, 6.0).s();            // 왼쪽 통로, (5,4) 근처
            assertThat(LOOP.nextCornerIndex(s, 2.0)).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("poseAt / cornerPose")
    class Poses {

        @Test
        @DisplayName("s=0 은 첫 모서리, 방향은 +y(북)")
        void start() {
            Track.Pose pose = LOOP.poseAt(0.0);
            assertThat(pose.x()).isCloseTo(15.5, within(1e-9));
            assertThat(pose.y()).isCloseTo(4.0, within(1e-9));
            assertThat(pose.headingDeg()).isCloseTo(90.0, within(1e-9));
        }

        @Test
        @DisplayName("위 홀에서는 -x(서)")
        void topSide() {
            Track.Pose pose = LOOP.poseAt(SIDE_Y + 5.0);
            assertThat(pose.y()).isCloseTo(27.0, within(1e-9));
            assertThat(pose.headingDeg()).isCloseTo(180.0, within(1e-9));
        }

        @Test
        @DisplayName("모서리 방향은 그 모서리를 돈 뒤 진행할 방향")
        void cornerHeading() {
            assertThat(LOOP.cornerPose(1).headingDeg()).isCloseTo(180.0, within(1e-9));
            assertThat(LOOP.cornerPose(2).headingDeg()).isCloseTo(270.0, within(1e-9));
        }

        @Test
        @DisplayName("음수·초과 s 도 접어서 처리한다")
        void wraps() {
            assertThat(LOOP.poseAt(67.0).x()).isCloseTo(LOOP.poseAt(0.0).x(), within(1e-9));
            assertThat(LOOP.poseAt(-1.0).y()).isCloseTo(LOOP.poseAt(66.0).y(), within(1e-9));
        }
    }

    @Nested
    @DisplayName("잘못된 입력은 기동 시점에 막는다")
    class Invalid {

        /** 여기서 안 막으면 gap() 이 0 나눗셈을 내고 관제가 조용히 멈춘다. */
        @Test
        @DisplayName("모서리가 2개 미만이면 거부")
        void tooFewCorners() {
            assertThatThrownBy(() -> new Track(List.of(new double[] {0, 0})))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Track(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("둘레가 0 이면 거부")
        void zeroLength() {
            assertThatThrownBy(() -> new Track(List.of(
                    new double[] {5, 5}, new double[] {5, 5})))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("좌표가 유한하지 않으면 거부")
        void nonFinite() {
            assertThatThrownBy(() -> new Track(List.of(
                    new double[] {0, 0}, new double[] {Double.NaN, 1})))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Track(List.of(
                    new double[] {0, 0}, new double[] {1})))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
