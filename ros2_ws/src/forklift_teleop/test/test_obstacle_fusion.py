import math
import unittest

from forklift_teleop.obstacle_fusion import (
    front_tof_corridors_from_points,
    AvoidanceAction,
    AvoidanceConfig,
    FrontTofClearance,
    LidarCorridors,
    VisionDetection,
    apply_decision,
    decide_avoidance,
    front_tof_distance_from_points,
    corridors_from_status,
    escape_yaw_sign,
    lidar_corridors_from_points,
    limit_reverse_yaw,
    preferred_avoidance_side,
    status_document,
    tail_swing_side_clearance,
    robust_nearest,
)


class ObstacleFusionTest(unittest.TestCase):
    def setUp(self):
        self.config = AvoidanceConfig()
        self.clear_lidar = LidarCorridors(2.0, 2.0, 2.0)
        self.clear_tof = FrontTofClearance(2.0, 2.0)

    def decide(self, lidar=None, tof=None, detections=(), missing=()):
        return decide_avoidance(
            lidar or self.clear_lidar,
            tof or self.clear_tof,
            detections,
            missing,
            self.config,
        )

    def test_clear_roof_and_fork_level_corridors_pass(self):
        decision = self.decide()
        self.assertEqual(decision.action, AvoidanceAction.CLEAR)
        self.assertEqual(
            apply_decision(0.1, 0.0, decision, 0.35),
            (0.1, 0.0),
        )

    def test_low_center_obstacle_seen_only_by_tof_stops(self):
        decision = self.decide(
            tof=FrontTofClearance(0.20, 2.0, front_path_m=0.20)
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_tall_center_obstacle_seen_by_roof_lidar_stops(self):
        decision = self.decide(
            lidar=LidarCorridors(2.0, 0.20, 2.0)
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_front_left_tof_obstacle_avoids_right_when_lidar_clear(self):
        # Inside avoidance_engage_distance_m -- outside it the guard slows
        # down and leaves the steering to the planner.
        decision = self.decide(
            tof=FrontTofClearance(0.35, 1.40, front_path_m=0.35)
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_RIGHT)
        linear, angular = apply_decision(0.1, 0.0, decision, 0.35)
        self.assertGreater(linear, 0.0)
        self.assertLess(linear, 0.1)
        self.assertLess(angular, 0.0)

    def test_front_right_tof_obstacle_avoids_left_when_lidar_clear(self):
        decision = self.decide(
            tof=FrontTofClearance(1.40, 0.35, front_path_m=0.35)
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_LEFT)

    def test_guard_leaves_steering_alone_outside_the_engage_distance(self):
        """Slowing down must not also mean seizing the wheel.

        The bias is bigger than what nav2 asks for, so adding it inverts the
        steering. Applied across the whole slowdown band it fought the planner
        every cycle and the vehicle never committed to a direction.
        """
        decision = self.decide(tof=FrontTofClearance(0.70, 1.40, front_path_m=0.70))
        self.assertEqual(decision.action, AvoidanceAction.SLOW)
        self.assertEqual(decision.yaw_bias_rps, 0.0)
        self.assertLess(decision.speed_scale, 1.0)
        _, angular = apply_decision(0.1, -0.05, decision, 0.35)
        self.assertAlmostEqual(angular, -0.05)

    def test_field_wall_at_43cm_avoids_left_instead_of_stopping(self):
        decision = self.decide(
            lidar=LidarCorridors(1.331, 0.468, 0.446),
            tof=FrontTofClearance(0.463, 0.431, front_path_m=0.431),
        )
        self.assertEqual(decision.action, AvoidanceAction.AVOID_LEFT)
        self.assertGreater(decision.yaw_bias_rps, 0.0)
        self.assertGreater(decision.speed_scale, 0.0)

    def test_tof_nominated_turn_is_rejected_when_lidar_side_blocked(self):
        decision = self.decide(
            lidar=LidarCorridors(2.0, 2.0, 0.40),
            tof=FrontTofClearance(0.70, 1.40, front_path_m=0.70),
        )
        self.assertEqual(decision.action, AvoidanceAction.SLOW)
        self.assertEqual(decision.yaw_bias_rps, 0.0)

    def test_person_or_vehicle_stops(self):
        for label in ("person", "forklift"):
            with self.subTest(label=label):
                decision = self.decide(detections=(
                    VisionDetection(label, 0.95, 1.0),
                ))
                self.assertEqual(decision.action, AvoidanceAction.STOP)

    def test_required_sensor_timeout_stops(self):
        decision = self.decide(missing=("front_right_tof",))
        self.assertEqual(decision.action, AvoidanceAction.SENSOR_TIMEOUT)

    def test_roof_lidar_points_are_split_in_base_frame(self):
        corridors = lidar_corridors_from_points(
            [
                (1.0, 0.7), (1.1, 0.8),
                (0.7, 0.0), (0.8, 0.0),
                (1.0, -0.7), (1.1, -0.8),
            ],
            math.radians(18),
            math.radians(70),
        )
        self.assertGreater(corridors.left_m, 1.0)
        self.assertEqual(corridors.center_m, 0.8)
        self.assertGreater(corridors.right_m, 1.0)

    def test_roof_lidar_extracts_rear_corridor(self):
        corridors = lidar_corridors_from_points(
            [(-0.7, 0.0), (-0.8, 0.02), (1.5, 0.0), (1.6, 0.0)],
            math.radians(18),
            math.radians(70),
        )
        self.assertAlmostEqual(corridors.rear_m, math.hypot(0.8, 0.02))


class RearSideCorridorTest(unittest.TestCase):
    """조향하며 후진할 때 꼬리가 쓸고 가는 고리를 본다.

    정후방 콘은 ±18° 뿐인데 꼬리는 18°~42° 로 나간다. 이 채널이 없으면
    **아무도 보지 않는 곳으로 차 뒤를 휘두르게 된다.**
    """

    CENTER = math.radians(18)
    FRONT = math.radians(70)
    REAR_SIDE = math.radians(60)

    def corridors(self, points):
        return lidar_corridors_from_points(
            points, self.CENTER, self.FRONT, 2, self.REAR_SIDE)

    def test_roof_lidar_splits_the_rear_into_left_and_right(self):
        """뒤-왼쪽은 rear_left, 뒤-오른쪽은 rear_right (REP-103: +y 가 왼쪽)."""
        corridors = self.corridors(
            [(-0.6, 0.3), (-0.61, 0.31), (-0.6, -0.5), (-0.61, -0.51)])
        self.assertLess(corridors.rear_left_m, math.inf)
        self.assertLess(corridors.rear_right_m, math.inf)
        # 오른쪽 점이 더 멀다
        self.assertGreater(corridors.rear_right_m, corridors.rear_left_m)

    def test_the_rear_stop_corridor_is_unchanged(self):
        """새 섹터가 rear_stop_distance_m 를 약화시키면 안 된다.

        두 구간은 겹치지 않게 잘랐다 -- 정후방 콘 값은 비트 단위로 같아야 한다.
        """
        points = [(-0.7, 0.0), (-0.8, 0.02), (1.5, 0.0), (1.6, 0.0)]
        before = lidar_corridors_from_points(points, self.CENTER, self.FRONT)
        after = self.corridors(points)
        self.assertEqual(after.rear_m, before.rear_m)
        self.assertAlmostEqual(after.rear_m, math.hypot(0.8, 0.02))
        # 정후방 점은 옆 채널로 새지 않는다
        self.assertEqual(after.rear_left_m, math.inf)
        self.assertEqual(after.rear_right_m, math.inf)

    def test_rear_side_ignores_an_abeam_wall(self):
        """정측면까지 열면 옆 평행벽이 후방 장애물로 읽힌다.

        전방 방위 구간에서 이미 겪은 실패다. 60° 는 그 지점에 못 닿는다.
        """
        corridors = self.corridors([(-0.1, -0.9), (-0.11, -0.91)])
        self.assertEqual(corridors.rear_right_m, math.inf)

    def test_default_rear_side_angle_of_zero_reproduces_the_old_split(self):
        """새 인자는 선택이다 -- 안 넘기면 종전 동작 그대로."""
        points = [(-0.6, 0.3), (-0.61, 0.31)]
        corridors = lidar_corridors_from_points(
            points, self.CENTER, self.FRONT)
        self.assertEqual(corridors.rear_left_m, math.inf)
        self.assertEqual(corridors.rear_right_m, math.inf)


class ReverseSteeringSignTest(unittest.TestCase):
    """후진 조향의 부호 -- 가장 뒤집어 쓰기 쉬운 곳."""

    def lidar(self, rear_left=math.inf, rear_right=math.inf):
        return LidarCorridors(rear_left_m=rear_left, rear_right_m=rear_right)

    def test_reversing_left_watches_the_right_rear(self):
        """좌회전 후진이면 꼬리는 **오른쪽**으로 나간다.

        순간회전중심이 y = v/ω 라, 후진(v<0)에 요가 양수면 중심이 오른쪽에
        잡힌다. 차체 뒤끝은 x<0 이라 횡속도 ω·x 의 부호가 뒤집히기 때문이다.
        test_pivot 의 상쇄 테스트와 같은 기하다.
        """
        lidar = self.lidar(rear_left=0.2, rear_right=0.9)
        self.assertEqual(tail_swing_side_clearance(lidar, 1.0), 0.9)
        self.assertEqual(tail_swing_side_clearance(lidar, -1.0), 0.2)
        self.assertEqual(tail_swing_side_clearance(lidar, 0.0), math.inf)

    def test_the_escape_sign_is_not_flipped_for_reverse(self):
        """전진과 같은 부호를 쓴다. 여기서 뒤집으면 두 번 뒤집혀 상쇄된다."""
        self.assertGreater(escape_yaw_sign(AvoidanceAction.AVOID_LEFT), 0.0)
        self.assertLess(escape_yaw_sign(AvoidanceAction.AVOID_RIGHT), 0.0)
        self.assertEqual(escape_yaw_sign(None), 0.0)

    def test_a_blocked_tail_side_zeroes_the_yaw_but_not_the_speed(self):
        """세우면 앞이 막힌 채로 갇힌다 -- 요만 접고 계속 물러난다."""
        config = AvoidanceConfig(reverse_tail_clearance_m=0.40)
        lidar = self.lidar(rear_right=0.20)
        self.assertEqual(limit_reverse_yaw(-0.12, 0.30, lidar, config), 0.0)

    def test_a_clear_tail_side_leaves_the_reverse_yaw_alone(self):
        config = AvoidanceConfig(reverse_tail_clearance_m=0.40)
        lidar = self.lidar(rear_right=0.90)
        self.assertEqual(limit_reverse_yaw(-0.12, 0.30, lidar, config), 0.30)

    def test_forward_commands_ignore_the_rear_side_channels(self):
        """전방 동작은 손대지 않는다."""
        config = AvoidanceConfig(reverse_tail_clearance_m=0.40)
        lidar = self.lidar(rear_right=0.05, rear_left=0.05)
        self.assertEqual(limit_reverse_yaw(0.12, 0.30, lidar, config), 0.30)


class StatusDocumentTest(unittest.TestCase):
    """가드가 쓰고 unstick 이 읽는다 -- 한쪽만 바뀌면 조용히 어긋난다."""

    def test_status_round_trips_through_corridors_from_status(self):
        lidar = LidarCorridors(0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
        tof = FrontTofClearance(0.11, 0.22, 0.33, 0.44)
        document = status_document("CLEAR", "reason", 1.0, lidar, tof, [])
        back_lidar, back_tof = corridors_from_status(document)
        self.assertEqual(back_lidar, lidar)
        self.assertEqual(back_tof, tof)

    def test_missing_keys_read_as_open_not_as_blocked(self):
        """옛 형식 문서가 와도 탈출이 죽으면 안 된다."""
        lidar, tof = corridors_from_status({"roofLidar": {"center": 0.4}})
        self.assertEqual(lidar.center_m, 0.4)
        self.assertEqual(lidar.rear_right_m, math.inf)
        self.assertEqual(tof.front_path_m, math.inf)

    def test_the_side_preference_is_reusable_on_its_own(self):
        """_choose_turn 에서 들어낸 판단이 그대로 살아 있어야 한다."""
        lidar = LidarCorridors(left_m=2.0, right_m=2.0)
        blocked_left = FrontTofClearance(front_left_m=0.2, front_right_m=0.9)
        side, _ = preferred_avoidance_side(lidar, blocked_left, 0.10, 0.15)
        self.assertEqual(side, AvoidanceAction.AVOID_RIGHT)

    def test_front_tof_filters_floor_and_outside_fork_cone(self):
        distance = front_tof_distance_from_points(
            [
                (0.20, 0.0, 0.0),       # floor: ignored
                (0.30, 0.5, 0.1),       # outside cone: ignored
                (0.60, 0.05, 0.1),
                (0.70, 0.05, 0.1),
            ],
            math.radians(35),
            0.02,
            0.80,
        )
        self.assertAlmostEqual(distance, math.hypot(0.70, 0.05))

    def test_isolated_speckle_is_rejected(self):
        self.assertEqual(robust_nearest([0.05, 0.80, 0.90]), 0.80)


class FrontTofCorridorsTest(unittest.TestCase):
    """A ToF grid must yield a left/right split, not one number per sensor.

    Collapsing each sensor to its nearest hit made the two read almost
    identically -- they cover the same forward cone -- so the imbalance test
    could never fire and every turn fell through to the roof LiDAR, which
    cannot see fork height at all.
    """

    FRONT = math.radians(35.0)
    CENTER = math.radians(12.0)

    def corridors(self, points):
        return front_tof_corridors_from_points(
            points, self.FRONT, self.CENTER, 0.02, 0.80, minimum_hits=2
        )

    def test_object_on_the_right_only_shortens_the_right_sector(self):
        points = [
            (1.50, +0.60, 0.20), (1.50, +0.62, 0.20),
            (0.40, -0.20, 0.20), (0.41, -0.21, 0.20),
            (1.50, +0.00, 0.20), (1.51, +0.01, 0.20),
        ]
        result = self.corridors(points)
        self.assertLess(result.front_right_m, 0.5)
        self.assertGreater(result.front_left_m, 1.0)
        self.assertGreater(result.front_left_m - result.front_right_m, 0.10)

    def test_floor_and_out_of_cone_points_are_dropped(self):
        points = [
            (0.30, 0.00, -0.05),
            (0.30, 0.00, 1.20),
            (0.30, 0.90, 0.20),
            (-0.5, 0.00, 0.20),
        ]
        result = self.corridors(points)
        self.assertEqual(result.front_left_m, math.inf)
        self.assertEqual(result.front_center_m, math.inf)
        self.assertEqual(result.front_right_m, math.inf)


class FrontCentreIsNotABlindSpotTest(unittest.TestCase):
    """A wall dead ahead must stop the vehicle.

    Splitting the ToF cone into bearing sectors moved the straight-ahead
    returns out of front_left_m/front_right_m and into their own field. For
    one afternoon nothing read that field, so the sector the fork actually
    drives into was the one sector no check covered, and the vehicle drove
    into walls.
    """

    def test_obstacle_dead_ahead_stops_even_with_the_sides_clear(self):
        decision = decide_avoidance(
            LidarCorridors(2.0, 2.0, 2.0, 2.0),
            FrontTofClearance(
                front_left_m=2.0,
                front_right_m=2.0,
                front_center_m=0.15,
                front_path_m=0.15,
            ),
            (),
            (),
            AvoidanceConfig(),
        )
        self.assertEqual(decision.action, AvoidanceAction.STOP)
        self.assertEqual(decision.speed_scale, 0.0)

    def test_centre_also_drives_the_slowdown(self):
        decision = decide_avoidance(
            LidarCorridors(2.0, 2.0, 2.0, 2.0),
            FrontTofClearance(
                front_left_m=2.0,
                front_right_m=2.0,
                front_center_m=0.60,
                front_path_m=0.60,
            ),
            (),
            (),
            AvoidanceConfig(),
        )
        self.assertLess(decision.speed_scale, 1.0)


class SweptPathStopTest(unittest.TestCase):
    """Stop for what is in the way, not for what is beside it.

    Defining "ahead" by bearing makes the corridor widen as things get closer:
    at 0.27 m the vehicle's half width subtends 15.3 degrees while the side
    sectors run from 12 to 35. Parked in a corner the guard read the side wall
    as a front obstacle and refused to move with the aisle wide open.
    """

    HALF_WIDTH = 0.11
    FRONT = math.radians(35.0)
    CENTER = math.radians(12.0)

    def corridors(self, points):
        return front_tof_corridors_from_points(
            points, self.FRONT, self.CENTER, 0.02, 0.80,
            minimum_hits=2, path_half_width_m=self.HALF_WIDTH,
        )

    def decide(self, tof):
        return decide_avoidance(
            LidarCorridors(2.0, 2.0, 2.0, 2.0), tof, (), (), AvoidanceConfig()
        )

    def test_a_wall_beside_the_vehicle_is_not_in_the_way(self):
        # 0.27 m ahead, 0.25 m to the side: 43 degrees off, well clear of a
        # body 0.074 m wide.
        points = [(0.27, -0.25, 0.20), (0.28, -0.26, 0.20),
                  (0.29, -0.27, 0.20), (0.30, -0.28, 0.20)]
        result = self.corridors(points)
        self.assertEqual(result.front_path_m, math.inf)
        self.assertNotEqual(self.decide(result).action, AvoidanceAction.STOP)

    def test_the_same_distance_dead_ahead_still_stops(self):
        points = [(0.20, 0.01, 0.20), (0.21, -0.02, 0.20),
                  (0.22, 0.00, 0.20)]
        result = self.corridors(points)
        self.assertLess(result.front_path_m, 0.25)
        self.assertEqual(self.decide(result).action, AvoidanceAction.STOP)

    def test_the_body_edge_counts_as_in_the_way(self):
        edge = self.HALF_WIDTH - 0.005
        points = [(0.27, edge, 0.20), (0.28, -edge, 0.20)]
        self.assertLess(self.corridors(points).front_path_m, 0.30)

    def test_side_sectors_still_report_for_turn_choice(self):
        """회전 방향을 고를 때는 통로 밖도 봐야 한다."""
        points = [(0.60, -0.30, 0.20), (0.61, -0.31, 0.20),
                  (1.50, 0.40, 0.20), (1.51, 0.41, 0.20)]
        result = self.corridors(points)
        self.assertLess(result.front_right_m, 0.8)
        self.assertGreater(result.front_left_m, 1.0)



class SteeredSlowdownReliefTest(unittest.TestCase):
    """꺾인 명령일수록 감속을 덜 한다.

    감속과 조향이 겹치면 차가 선다 -- 곧게 갈 때 도는 듀티는 후륜이 옆으로
    갈아내는 상태를 못 이긴다. 그런데 가드는 장애물에 다가갈수록 속도를
    줄이고, 회피는 바로 그때 꺾는다. "피하려고 꺾었는데 그 자리에 서는"
    상태가 여기서 나온다.
    """

    FULL = 1.0 / 0.60          # 이보다 급하면 감속을 전혀 안 한다

    def slow(self, scale=0.5, bias=0.0):
        from forklift_teleop.obstacle_fusion import AvoidanceDecision
        return AvoidanceDecision(AvoidanceAction.SLOW, scale, bias, "test")

    def test_a_straight_command_is_slowed_in_full(self):
        """곧은 구간의 감속은 제동거리를 벌어 준다 -- 그대로 둔다."""
        linear, _ = apply_decision(0.20, 0.0, self.slow(), 2.5, 1.0, self.FULL)
        self.assertAlmostEqual(linear, 0.10)

    def test_a_turning_command_is_not_slowed_at_all(self):
        """회전 중에는 감속하지 않는다 -- 느려지면 그 자리에 선다."""
        # 곡률 1/0.5 > 기준(1/0.6) -> 완화 100%
        linear, _ = apply_decision(0.10, 0.20, self.slow(), 2.5, 1.0, self.FULL)
        self.assertAlmostEqual(linear, 0.10)

    def test_a_gentle_curve_gets_partial_relief(self):
        """완만한 곡선은 아직 스톨 구간이 아니다 -- 감속을 조금 남긴다."""
        # 곡률 1/1.2 = 기준의 절반
        linear, _ = apply_decision(0.12, 0.10, self.slow(), 2.5, 1.0, self.FULL)
        self.assertGreater(linear, 0.12 * 0.5)
        self.assertLess(linear, 0.12)

    def test_relief_of_zero_reproduces_the_old_behaviour(self):
        """새 인자는 선택이다 -- 안 넘기면 종전 그대로."""
        linear, _ = apply_decision(0.10, 0.40, self.slow(), 2.5)
        self.assertAlmostEqual(linear, 0.05)

    def test_relief_never_speeds_the_vehicle_past_the_command(self):
        """완화는 감속을 되돌릴 뿐, 명령보다 빨라지지 않는다."""
        linear, _ = apply_decision(0.10, 4.0, self.slow(), 2.5, 1.0, self.FULL)
        self.assertLessEqual(linear, 0.10 + 1e-9)

    def test_the_steering_command_itself_is_untouched(self):
        """완화는 속도 이야기다 -- 조향을 건드리면 가드가 운전대를 뺏는 것이다."""
        _, angular = apply_decision(0.10, 0.40, self.slow(), 2.5, 1.0, self.FULL)
        self.assertAlmostEqual(angular, 0.40)


if __name__ == "__main__":
    unittest.main()
