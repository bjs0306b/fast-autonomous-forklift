package com.fast.backend.traffic.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 주기 진행 상태 검증. 규격이 지적한 사고들을 그대로 테스트로 고정한다. */
class VehicleCycleTest {

    private static final long T0 = 1_000_000L;

    @Nested
    @DisplayName("단계 전이")
    class Phases {

        @Test
        @DisplayName("규격 순서대로 돈다")
        void order() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            assertThat(cycle.phase()).isEqualTo(CyclePhase.TO_BAY);

            CyclePhase[] expected = {
                    CyclePhase.ALIGN_BAY, CyclePhase.LOAD, CyclePhase.TO_EXIT,
                    CyclePhase.TO_RACK, CyclePhase.RACK, CyclePhase.TO_BAY};
            for (CyclePhase phase : expected) {
                cycle.advance(T0);
                assertThat(cycle.phase()).isEqualTo(phase);
            }
        }

        @Test
        @DisplayName("RACK 을 지나면 주기 수가 오르고 랙 배정이 풀린다")
        void cycleCount() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            for (int i = 0; i < 5; i++) {
                cycle.advance(T0);      // → RACK
            }
            cycle.assignRack("A001");
            assertThat(cycle.cycles()).isZero();

            cycle.advance(T0);          // RACK → TO_BAY
            assertThat(cycle.cycles()).isEqualTo(1);
            assertThat(cycle.rackCode()).isNull();
        }

        @Test
        @DisplayName("작업 단계는 제자리에서 한다")
        void working() {
            assertThat(CyclePhase.ALIGN_BAY.isWorking()).isTrue();
            assertThat(CyclePhase.LOAD.isWorking()).isTrue();
            assertThat(CyclePhase.RACK.isWorking()).isTrue();
            assertThat(CyclePhase.TO_BAY.isWorking()).isFalse();
            assertThat(CyclePhase.TO_RACK.isWorking()).isFalse();
        }

        @Test
        @DisplayName("바이를 점유하는 단계는 셋")
        void bay() {
            assertThat(CyclePhase.TO_BAY.occupiesBay()).isTrue();
            assertThat(CyclePhase.ALIGN_BAY.occupiesBay()).isTrue();
            assertThat(CyclePhase.LOAD.occupiesBay()).isTrue();
            assertThat(CyclePhase.TO_EXIT.occupiesBay()).isFalse();
        }
    }

    @Nested
    @DisplayName("목표 중복 발행 방지 — 규격 §10 함정 5번")
    class Goals {

        @Test
        @DisplayName("같은 목표는 한 번만 보낸다")
        void once() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            assertThat(cycle.shouldSendGoal("BAY")).isTrue();
            assertThat(cycle.shouldSendGoal("BAY")).isFalse();
            assertThat(cycle.shouldSendGoal("BAY")).isFalse();
        }

        @Test
        @DisplayName("목표가 바뀌면 다시 보낸다")
        void changed() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.shouldSendGoal("BAY");
            assertThat(cycle.shouldSendGoal("C1")).isTrue();
        }

        /** 안 지우면 새 단계의 첫 목표가 "이미 보냈다"로 걸러진다. */
        @Test
        @DisplayName("단계가 바뀌면 목표 기억이 지워진다")
        void clearedOnAdvance() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.shouldSendGoal("BAY");
            cycle.advance(T0);
            assertThat(cycle.lastGoal()).isNull();
            assertThat(cycle.shouldSendGoal("BAY")).isTrue();
        }

        @Test
        @DisplayName("forgetGoal 후에는 같은 목표도 다시 보낸다")
        void forget() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.shouldSendGoal("BAY");
            cycle.forgetGoal();
            assertThat(cycle.shouldSendGoal("BAY")).isTrue();
        }

        @Test
        @DisplayName("null 목표는 보내지 않는다")
        void nullGoal() {
            assertThat(new VehicleCycle("SIM-F02", T0).shouldSendGoal(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("정체 감시 — 규칙 4")
    class Stall {

        private static final double MOVE = 0.3;
        private static final long STALL_MS = 20_000L;

        @Test
        @DisplayName("첫 관측은 정체가 아니다")
        void firstSample() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            assertThat(cycle.updateAndCheckStall(5.0, 10.0, T0, MOVE, STALL_MS)).isFalse();
        }

        @Test
        @DisplayName("움직이면 정체가 아니다")
        void moving() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.updateAndCheckStall(5.0, 10.0, T0, MOVE, STALL_MS);
            assertThat(cycle.updateAndCheckStall(5.0, 11.0, T0 + 30_000, MOVE, STALL_MS)).isFalse();
        }

        @Test
        @DisplayName("제자리에서 20초가 지나면 정체")
        void stalled() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.updateAndCheckStall(5.0, 10.0, T0, MOVE, STALL_MS);
            assertThat(cycle.updateAndCheckStall(5.0, 10.1, T0 + 10_000, MOVE, STALL_MS)).isFalse();
            assertThat(cycle.updateAndCheckStall(5.0, 10.1, T0 + 21_000, MOVE, STALL_MS)).isTrue();
        }

        /** 판정 후 시각을 안 밀면 매 tick 정체로 잡혀 목표가 초당 두 번 재전송된다. */
        @Test
        @DisplayName("정체 판정 직후에는 다시 정체로 잡지 않는다")
        void notImmediatelyAgain() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.updateAndCheckStall(5.0, 10.0, T0, MOVE, STALL_MS);
            assertThat(cycle.updateAndCheckStall(5.0, 10.0, T0 + 21_000, MOVE, STALL_MS)).isTrue();
            assertThat(cycle.updateAndCheckStall(5.0, 10.0, T0 + 21_500, MOVE, STALL_MS)).isFalse();
        }

        /** 세워 둔 동안 시각을 안 밀면 해제 직후 정체로 오판한다 — 규격 §10 함정 7번. */
        @Test
        @DisplayName("touchStallTimer 로 정지 중 오판을 막는다")
        void touch() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.updateAndCheckStall(5.0, 10.0, T0, MOVE, STALL_MS);
            cycle.touchStallTimer(T0 + 30_000);     // 세워 둔 동안 계속 밀어 둔다
            assertThat(cycle.updateAndCheckStall(5.0, 10.0, T0 + 40_000, MOVE, STALL_MS)).isFalse();
        }
    }

    @Nested
    @DisplayName("작업 시간 상한")
    class Timeout {

        @Test
        @DisplayName("상한을 넘겼는지 판정한다")
        void timedOut() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            assertThat(cycle.workTimedOut(T0 + 5_000, 7_000)).isFalse();
            assertThat(cycle.workTimedOut(T0 + 8_000, 7_000)).isTrue();
        }

        @Test
        @DisplayName("단계가 바뀌면 타이머가 새로 시작한다")
        void resetOnAdvance() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.advance(T0 + 10_000);
            assertThat(cycle.workTimedOut(T0 + 15_000, 7_000)).isFalse();
        }
    }

    @Nested
    @DisplayName("최소 대기 — 너무 빨리 넘어가는 것을 막는다")
    class MinimumDwell {

        /**
         * 이게 없으면 정렬 지시 직후 차량이 아직 IDLE 일 때 {@code state != LOADING} 이
         * 곧바로 참이 되어, 정렬을 시작하지도 않고 다음 단계로 넘어간다.
         */
        @Test
        @DisplayName("지시 직후에는 아직 안 지났다")
        void notSettledImmediately() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            assertThat(cycle.workSettled(T0, 1_500)).isFalse();
            assertThat(cycle.workSettled(T0 + 500, 1_500)).isFalse();
            assertThat(cycle.workSettled(T0 + 1_499, 1_500)).isFalse();
        }

        @Test
        @DisplayName("최소 시간이 지나면 참")
        void settled() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            assertThat(cycle.workSettled(T0 + 1_500, 1_500)).isTrue();
            assertThat(cycle.workSettled(T0 + 3_000, 1_500)).isTrue();
        }

        @Test
        @DisplayName("단계가 바뀌면 최소 대기도 새로 시작한다")
        void resetOnAdvance() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.advance(T0 + 10_000);
            assertThat(cycle.workSettled(T0 + 10_500, 1_500)).isFalse();
            assertThat(cycle.workSettled(T0 + 11_500, 1_500)).isTrue();
        }

        @Test
        @DisplayName("0 이면 즉시 통과한다")
        void zero() {
            assertThat(new VehicleCycle("SIM-F02", T0).workSettled(T0, 0)).isTrue();
        }
    }

    @Nested
    @DisplayName("실패 복구")
    class Restart {

        @Test
        @DisplayName("주기 수를 올리지 않는다 — 실패를 완료로 세면 지표가 거짓말이 된다")
        void 재시작은_주기_수를_올리지_않는다() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.advance(T0);      // ALIGN_BAY
            cycle.advance(T0);      // LOAD — 여기서 적재에 실패했다고 하자

            cycle.restartToBay(T0 + 40_000);

            assertThat(cycle.phase()).isEqualTo(CyclePhase.TO_BAY);
            assertThat(cycle.cycles()).isZero();
        }

        @Test
        @DisplayName("정상 완료(RACK 통과)는 그대로 주기 수를 올린다")
        void 정상_완료는_주기_수를_올린다() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            for (int i = 0; i < 6; i++) {
                cycle.advance(T0);      // TO_BAY → ... → RACK → TO_BAY
            }
            assertThat(cycle.phase()).isEqualTo(CyclePhase.TO_BAY);
            assertThat(cycle.cycles()).isEqualTo(1);
        }

        @Test
        @DisplayName("배정된 랙과 목표 기억을 지운다 — 다음 주기가 이전 랙을 물고 가지 않게")
        void 재시작은_랙_배정을_지운다() {
            VehicleCycle cycle = new VehicleCycle("SIM-F02", T0);
            cycle.assignRack("A001");
            cycle.shouldSendGoal("BAY");

            cycle.restartToBay(T0 + 1_000);

            assertThat(cycle.rackCode()).isNull();
            assertThat(cycle.target()).isEqualTo("BAY");
            assertThat(cycle.shouldSendGoal("BAY")).isTrue();
        }
    }
}
