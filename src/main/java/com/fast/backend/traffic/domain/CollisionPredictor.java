package com.fast.backend.traffic.domain;

import java.util.Collection;
import java.util.Optional;

/**
 * 충돌·안전거리 판단의 <b>순수 함수</b> 모음 (FR-502-1a).
 *
 * <p>스프링·DB·MQTT 를 전혀 모르게 두었다. 이 로직이 잘못되면 차량이 안 멈추거나 영영 못 가는데,
 * 그런 버그는 통합 환경에서 재현하기 어렵다. 그래서 판단은 전부 여기 모아 단위 테스트로 고정한다.
 *
 * <p><b>예측 모델은 의도적으로 단순하다</b> — 현재 속도·방향이 예측 구간 동안 유지된다고 본다(등속
 * 직선). 실제 차량은 곡선 주행을 하므로 이 예측은 정확한 미래가 아니라 <b>"이대로 가면 위험한가"의
 * 조기 경보</b>다. 더 정교한 경로 예측(Nav2 plan 구독 등)은 ROS2 쪽 계약이 확정된 뒤에 얹는다.
 */
public final class CollisionPredictor {

    private CollisionPredictor() {
    }

    /** 두 지점 사이 거리(m). */
    public static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }

    /** 현재 두 차량 사이 거리(m). */
    public static double currentGap(VehicleMotion a, VehicleMotion b) {
        return distance(a.x(), a.y(), b.x(), b.y());
    }

    /**
     * {@code horizonS} 초 뒤 예상 위치. 속도·방향을 모르면 <b>현재 위치를 그대로</b> 돌려준다.
     *
     * <p>모를 때 움직인다고 가정하지 않는 이유: 방향을 모르는 채 아무 방향으로나 이동시키면 실제와
     * 무관한 근접을 만들어 <b>엉뚱한 차량을 세운다</b>. 대신 "모르는 차량은 예측하지 않는다"로 두고,
     * 현재 거리만으로 판단하게 한다(현재 거리 판정은 그대로 살아 있다).
     */
    public static double[] predictPosition(VehicleMotion motion, double horizonS) {
        if (!motion.hasKnownVelocity()) {
            return new double[] {motion.x(), motion.y()};
        }
        double rad = Math.toRadians(motion.headingDeg());
        double dist = motion.speedMps() * horizonS;
        return new double[] {
                motion.x() + Math.cos(rad) * dist,
                motion.y() + Math.sin(rad) * dist,
        };
    }

    /** {@code horizonS} 초 뒤 두 차량 사이 예상 거리(m). */
    public static double predictedGap(VehicleMotion a, VehicleMotion b, double horizonS) {
        double[] pa = predictPosition(a, horizonS);
        double[] pb = predictPosition(b, horizonS);
        return distance(pa[0], pa[1], pb[0], pb[1]);
    }

    /**
     * 판단에 쓸 거리 — <b>현재와 예측 중 더 가까운 쪽</b>.
     *
     * <p>둘 중 하나만 보면 놓치는 경우가 있다. 예측만 보면 이미 붙어 있는데 서로 멀어지는 중이라
     * "안전"으로 읽히고, 현재만 보면 아직 멀지만 빠르게 접근하는 상황을 놓친다. 안전 쪽으로 기운다.
     */
    public static double effectiveGap(VehicleMotion a, VehicleMotion b, double horizonS) {
        return Math.min(currentGap(a, b), predictedGap(a, b, horizonS));
    }

    /**
     * {@code follower} 가 {@code leader} 에게 <b>접근 중</b>인가.
     *
     * <p>속도 벡터가 상대 방향을 향하는지(내적 &gt; 0)로 본다. 접근하지 않는 차량(멀어지는 중)을
     * 세우는 것은 불필요한 정지다.
     */
    public static boolean isClosing(VehicleMotion follower, VehicleMotion leader) {
        if (!follower.hasKnownVelocity() || follower.speedMps() <= 0) {
            return false;
        }
        double rad = Math.toRadians(follower.headingDeg());
        double vx = Math.cos(rad);
        double vy = Math.sin(rad);
        double dx = leader.x() - follower.x();
        double dy = leader.y() - follower.y();
        return (vx * dx + vy * dy) > 0;
    }

    /**
     * 한 쌍에서 <b>누구를 세울 것인가</b>. 세울 필요가 없으면 빈 값.
     *
     * <p>규칙(위에서부터 우선):
     * <ol>
     *   <li>한쪽이 작업 중({@link VehicleMotion#isWorking()})이면 <b>다른 쪽</b>을 세운다 —
     *       하역 중인 차를 세워 봐야 이미 서 있고, 작업만 늦어진다</li>
     *   <li>접근 중인 쪽을 세운다</li>
     *   <li>둘 다 접근 중이면 <b>둘 다</b> 세워야 하므로 여기서는 정할 수 없다 —
     *       호출부가 양쪽 모두에 대해 이 함수를 부르므로 각자 자기 차례에 걸린다</li>
     * </ol>
     *
     * @return 정지시켜야 할 차량 ID
     */
    public static Optional<String> resolveWhoStops(VehicleMotion a, VehicleMotion b) {
        boolean aWorking = a.isWorking();
        boolean bWorking = b.isWorking();
        if (aWorking && !bWorking) return Optional.of(b.vehicleId());
        if (bWorking && !aWorking) return Optional.of(a.vehicleId());

        boolean aClosing = isClosing(a, b);
        boolean bClosing = isClosing(b, a);
        if (aClosing && !bClosing) return Optional.of(a.vehicleId());
        if (bClosing && !aClosing) return Optional.of(b.vehicleId());
        // 둘 다 접근 중이거나 둘 다 아니면 이 함수로는 못 정한다(위 3번 주석).
        return Optional.empty();
    }

    /**
     * 이 차량의 <b>현재 또는 예상 위치</b>가 남의 점유 구역에 걸리는가.
     *
     * <p>자기가 점유한 구역은 제외한다 — 하역하러 들어간 차량이 자기 구역 때문에 멈추면 작업이
     * 시작되지 않는다.
     */
    public static Optional<WorkZone> firstBlockingZone(
            VehicleMotion motion, Collection<WorkZone> zones, double horizonS) {
        double[] predicted = predictPosition(motion, horizonS);
        for (WorkZone zone : zones) {
            if (!zone.isOccupied() || zone.isOccupiedBy(motion.vehicleId())) {
                continue;
            }
            if (zone.contains(motion.x(), motion.y()) || zone.contains(predicted[0], predicted[1])) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }
}
