package com.fast.backend.traffic.domain;

import com.fast.backend.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 충돌 판단 순수 로직 테스트 (FR-502-1a).
 *
 * <p>이 로직이 틀리면 차량이 안 멈추거나(위험) 영영 못 간다(시연 정지). 통합 환경에서는 재현이
 * 어려운 종류라 여기서 고정한다.
 */
class CollisionPredictorTest {

    /** 동쪽(+X)으로 speed 만큼 달리는 차량. */
    private static VehicleMotion moving(String id, double x, double y, double headingDeg, double speed) {
        return new VehicleMotion(id, x, y, headingDeg, speed, VehicleStatus.MOVING);
    }

    private static VehicleMotion stopped(String id, double x, double y) {
        return new VehicleMotion(id, x, y, 0.0, 0.0, VehicleStatus.IDLE);
    }

    private static VehicleMotion working(String id, double x, double y) {
        return new VehicleMotion(id, x, y, 0.0, 0.0, VehicleStatus.UNLOADING);
    }

    @Nested
    @DisplayName("예측 위치")
    class Prediction {

        @Test
        void 동쪽으로_2초간_1mps면_2m_이동한다() {
            double[] p = CollisionPredictor.predictPosition(moving("A", 0, 0, 0, 1.0), 2.0);
            assertThat(p[0]).isCloseTo(2.0, within(1e-9));
            assertThat(p[1]).isCloseTo(0.0, within(1e-9));
        }

        @Test
        void 북쪽_90도면_Y가_증가한다() {
            double[] p = CollisionPredictor.predictPosition(moving("A", 0, 0, 90, 2.0), 1.0);
            assertThat(p[0]).isCloseTo(0.0, within(1e-9));
            assertThat(p[1]).isCloseTo(2.0, within(1e-9));
        }

        /** 모르는 값을 임의로 채우면 엉뚱한 차량을 세우게 된다(클래스 주석 참고). */
        @Test
        void 속도를_모르면_현재_위치를_그대로_쓴다() {
            VehicleMotion unknown = new VehicleMotion("A", 5, 7, null, null, VehicleStatus.UNKNOWN);
            double[] p = CollisionPredictor.predictPosition(unknown, 10.0);
            assertThat(p).containsExactly(5.0, 7.0);
        }
    }

    @Nested
    @DisplayName("거리 판정")
    class Gap {

        @Test
        void 접근_중이면_예측_거리가_현재보다_짧다() {
            VehicleMotion follower = moving("F", 0, 0, 0, 2.0);   // 동쪽으로 2m/s
            VehicleMotion leader = stopped("L", 10, 0);

            assertThat(CollisionPredictor.currentGap(follower, leader)).isCloseTo(10.0, within(1e-9));
            assertThat(CollisionPredictor.predictedGap(follower, leader, 2.0)).isCloseTo(6.0, within(1e-9));
        }

        /** 현재만 보거나 예측만 보면 놓치는 경우가 있어 둘 중 가까운 쪽을 쓴다. */
        @Test
        void 유효거리는_현재와_예측_중_가까운_쪽이다() {
            VehicleMotion approaching = moving("F", 0, 0, 0, 2.0);
            VehicleMotion leader = stopped("L", 10, 0);
            assertThat(CollisionPredictor.effectiveGap(approaching, leader, 2.0))
                    .isCloseTo(6.0, within(1e-9));

            // 이미 붙어 있지만 멀어지는 중 — 예측만 보면 "안전"으로 잘못 읽힌다.
            VehicleMotion leaving = moving("F", 9, 0, 180, 2.0);  // 서쪽으로
            VehicleMotion near = stopped("L", 10, 0);
            assertThat(CollisionPredictor.effectiveGap(leaving, near, 2.0))
                    .isCloseTo(1.0, within(1e-9));
        }
    }

    @Nested
    @DisplayName("접근 판정")
    class Closing {

        @Test
        void 상대를_향해_가면_접근이다() {
            assertThat(CollisionPredictor.isClosing(moving("F", 0, 0, 0, 1.0), stopped("L", 10, 0)))
                    .isTrue();
        }

        @Test
        void 반대로_가면_접근이_아니다() {
            assertThat(CollisionPredictor.isClosing(moving("F", 0, 0, 180, 1.0), stopped("L", 10, 0)))
                    .isFalse();
        }

        @Test
        void 정지_중이면_접근이_아니다() {
            assertThat(CollisionPredictor.isClosing(stopped("F", 0, 0), stopped("L", 10, 0))).isFalse();
        }
    }

    @Nested
    @DisplayName("누구를 세울 것인가")
    class WhoStops {

        /** 하역 중인 차를 세워 봐야 이미 서 있고 작업만 늦어진다. */
        @Test
        void 한쪽이_작업_중이면_반대쪽을_세운다() {
            Optional<String> who = CollisionPredictor.resolveWhoStops(
                    working("LEADER", 10, 0), moving("FOLLOWER", 0, 0, 0, 1.0));
            assertThat(who).contains("FOLLOWER");
        }

        @Test
        void 접근하는_쪽을_세운다() {
            Optional<String> who = CollisionPredictor.resolveWhoStops(
                    moving("FOLLOWER", 0, 0, 0, 1.0), stopped("LEADER", 10, 0));
            assertThat(who).contains("FOLLOWER");
        }

        /** 둘 다 접근이면 여기서 정하지 않는다 — 호출부가 양쪽 각각을 평가한다. */
        @Test
        void 마주_달리면_한쪽으로_정하지_않는다() {
            Optional<String> who = CollisionPredictor.resolveWhoStops(
                    moving("A", 0, 0, 0, 1.0), moving("B", 10, 0, 180, 1.0));
            assertThat(who).isEmpty();
        }
    }

    @Nested
    @DisplayName("작업 구역 침범")
    class Zones {

        private final WorkZone occupied = new WorkZone("A1", 10, 0, 3.0, "LEADER");

        @Test
        void 남의_점유_구역에_들어가면_걸린다() {
            Optional<WorkZone> blocking = CollisionPredictor.firstBlockingZone(
                    moving("FOLLOWER", 8, 0, 0, 1.0), List.of(occupied), 2.0);
            assertThat(blocking).isPresent();
            assertThat(blocking.get().slotCode()).isEqualTo("A1");
        }

        /** 아직 밖이지만 이대로 가면 들어간다 — 조기에 세우는 것이 이 기능의 요지다. */
        @Test
        void 예상_경로가_구역에_닿아도_걸린다() {
            Optional<WorkZone> blocking = CollisionPredictor.firstBlockingZone(
                    moving("FOLLOWER", 4, 0, 0, 2.0), List.of(occupied), 2.0);
            assertThat(blocking).isPresent();
        }

        /** 자기 구역 때문에 멈추면 하역이 시작되지 않는다. */
        @Test
        void 점유자_본인은_걸리지_않는다() {
            Optional<WorkZone> blocking = CollisionPredictor.firstBlockingZone(
                    working("LEADER", 10, 0), List.of(occupied), 2.0);
            assertThat(blocking).isEmpty();
        }

        @Test
        void 비어_있는_구역은_걸리지_않는다() {
            WorkZone free = new WorkZone("A1", 10, 0, 3.0, null);
            Optional<WorkZone> blocking = CollisionPredictor.firstBlockingZone(
                    moving("F", 10, 0, 0, 1.0), List.of(free), 2.0);
            assertThat(blocking).isEmpty();
        }

        @Test
        void 멀리_있으면_걸리지_않는다() {
            Optional<WorkZone> blocking = CollisionPredictor.firstBlockingZone(
                    moving("F", 0, 0, 90, 1.0), List.of(occupied), 2.0);
            assertThat(blocking).isEmpty();
        }
    }
}
