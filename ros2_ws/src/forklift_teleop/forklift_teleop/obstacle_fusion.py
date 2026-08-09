"""Pure policy for roof-LiDAR and dual front-ToF obstacle avoidance."""

from dataclasses import dataclass
from enum import Enum
import math
from typing import Iterable, Optional, Sequence


class AvoidanceAction(str, Enum):
    CLEAR = "CLEAR"
    SLOW = "SLOW"
    AVOID_LEFT = "AVOID_LEFT"
    AVOID_RIGHT = "AVOID_RIGHT"
    STOP = "STOP"
    SENSOR_TIMEOUT = "SENSOR_TIMEOUT"


@dataclass(frozen=True)
class LidarCorridors:
    """Roof-LiDAR clearance in the common ``base_link`` frame."""

    left_m: float = math.inf
    center_m: float = math.inf
    right_m: float = math.inf
    rear_m: float = math.inf


@dataclass(frozen=True)
class FrontTofClearance:
    """Clearance in each forward bearing sector at fork height.

    These are bearing sectors, not sensors. Reducing each sensor to one
    number made the two nearly equal -- both face forward over overlapping
    cones, so they see the same nearest object -- and the imbalance test in
    _choose_turn could never fire. Every turn decision then fell through to
    the roof LiDAR, which is blind at fork height. The ToF grid carries an
    azimuth per zone; splitting on it is what makes a left/right choice at
    fork height possible at all.
    """

    front_left_m: float = math.inf
    front_right_m: float = math.inf
    front_center_m: float = math.inf
    # 차체가 실제로 쓸고 갈 통로 안의 최근접. 정지 판정은 이것만 본다.
    #
    # 방위 구간으로 정지를 걸면 **옆에 있는 것에 멈춘다.** 0.27 m 거리에서
    # 차체 반폭 0.074 m 가 차지하는 각은 15.3° 뿐인데, 좌/우 구간은 12~35° 라
    # 통로 밖이 대부분이다. 모서리에 세워 두면 옆벽을 보고 "정면 장애물" 로
    # 읽어 앞이 훤히 비어 있어도 못 나간다.
    front_path_m: float = math.inf


@dataclass(frozen=True)
class VisionDetection:
    label: str
    confidence: float
    distance_m: Optional[float] = None


# 회전 중 옆으로 필요한 최소 여유. 차체 반폭 0.074 + 꼬리 휨 0.182 이다.
# 후륜 조향차가 최소반경으로 돌면 바깥 뒷모서리가 회전반경보다 그만큼 더
# 나가므로, 앞이 지나갈 수 있다고 보고 돌기 시작하면 뒤가 걸린다.
# 근거: tools/geometry_limits.py.
TAIL_SWING_CLEARANCE_M = 0.256


@dataclass(frozen=True)
class AvoidanceConfig:
    # Distances are in base_link, while the footprint reaches x=0.190 m.
    # Field override approved at 0.25 m, leaving 0.06 m body clearance.
    stop_distance_m: float = 0.25
    slowdown_distance_m: float = 1.00
    # Below this the guard may bias steering; between here and
    # slowdown_distance_m it only slows down.
    #
    # The bias is added to the incoming command, and at 0.15 rad/s it is
    # larger than the 0.03-0.12 rad/s nav2 actually asks for -- so adding it
    # inverts the steering rather than nudging it. With the engage distance
    # equal to slowdown_distance_m that happened through the whole operating
    # band: on 2026-08-07 the guard reversed nav2's steering in 9 of 15
    # commands, the rear steering swung side to side, and the vehicle never
    # committed to a direction long enough to overcome static friction.
    avoidance_engage_distance_m: float = 0.45
    # The measured drive cannot overcome static friction after a 0.25 scale,
    # and a fixed 0.18 rad/s bias saturates rear steering at that low speed.
    minimum_speed_scale: float = 0.80
    avoidance_yaw_rate_rps: float = 0.02
    tof_imbalance_m: float = 0.10
    lidar_clearance_margin_m: float = 0.15
    minimum_turn_clearance_m: float = 0.55
    vision_confidence_threshold: float = 0.60
    dynamic_object_stop_distance_m: float = 1.50
    dynamic_labels: tuple = (
        "person",
        "pedestrian",
        "forklift",
        "vehicle",
        "car",
        "truck",
    )

    def validate(self) -> None:
        if self.stop_distance_m <= 0.0:
            raise ValueError("stop_distance_m must be positive")
        if self.slowdown_distance_m <= self.stop_distance_m:
            raise ValueError(
                "slowdown_distance_m must be greater than stop_distance_m"
            )
        if not 0.0 < self.minimum_speed_scale <= 1.0:
            raise ValueError("minimum_speed_scale must be in (0, 1]")
        if min(
            self.avoidance_yaw_rate_rps,
            self.tof_imbalance_m,
            self.lidar_clearance_margin_m,
        ) < 0.0:
            raise ValueError("avoidance thresholds cannot be negative")
        # ⚠️ **옆은 앞과 다른 기하다.** 종전에는 회전 여유가 전방 정지거리보다
        #    커야 한다고 묶어 두었는데, 두 값이 재는 것이 다르다:
        #
        #      앞  = 제동거리 + 차체 앞끝 0.190  -> 들이받는 문제
        #      옆  = 차체 반폭 0.074 + 꼬리 휨 0.182 = 0.256 -> 스치는 문제
        #
        #    묶어 두면 옆을 앞보다 과감하게 둘 수 없고, 그 결과 목업 벽을 따라
        #    선 차는 회전 판정이 아예 안 나 앞뒤로만 흔들렸다(2026-08-08).
        #    이제 하한은 꼬리 휨이 정한다. tools/geometry_limits.py 가 근거다.
        if self.minimum_turn_clearance_m < TAIL_SWING_CLEARANCE_M:
            raise ValueError(
                "minimum_turn_clearance_m 은 꼬리 휨 하한 "
                f"{TAIL_SWING_CLEARANCE_M:.3f} m 이상이어야 한다 -- "
                "그보다 작으면 회전 중 바깥 뒷모서리가 닿는다"
            )
        if not (self.stop_distance_m < self.avoidance_engage_distance_m
                <= self.slowdown_distance_m):
            raise ValueError(
                "avoidance_engage_distance_m must be between "
                "stop_distance_m and slowdown_distance_m"
            )
        if not 0.0 <= self.vision_confidence_threshold <= 1.0:
            raise ValueError("vision_confidence_threshold must be in [0, 1]")


@dataclass(frozen=True)
class AvoidanceDecision:
    action: AvoidanceAction
    speed_scale: float
    yaw_bias_rps: float
    reason: str


def robust_nearest(
    values: Iterable[float],
    minimum_hits: int = 2,
) -> float:
    """Return the Nth-nearest finite hit to reject one-pixel speckle."""
    valid = sorted(
        value for value in values
        if math.isfinite(value) and value > 0.0
    )
    if not valid:
        return math.inf
    index = min(max(minimum_hits, 1), len(valid)) - 1
    return valid[index]


def lidar_corridors_from_points(
    points_xy: Iterable[tuple],
    center_half_angle_rad: float,
    front_half_angle_rad: float,
    minimum_hits: int = 2,
) -> LidarCorridors:
    """Split roof-LiDAR points into center, left-turn and right-turn space."""
    left = []
    center = []
    right = []
    rear = []
    for x, y in points_xy:
        if not (math.isfinite(x) and math.isfinite(y)):
            continue
        distance = math.hypot(x, y)
        if distance <= 0.0:
            continue
        angle = math.atan2(y, x)
        if abs(abs(angle) - math.pi) <= center_half_angle_rad:
            rear.append(distance)
        if abs(angle) > front_half_angle_rad:
            continue
        if abs(angle) <= center_half_angle_rad:
            center.append(distance)
        elif angle > 0.0:
            left.append(distance)
        else:
            right.append(distance)
    return LidarCorridors(
        left_m=robust_nearest(left, minimum_hits),
        center_m=robust_nearest(center, minimum_hits),
        right_m=robust_nearest(right, minimum_hits),
        rear_m=robust_nearest(rear, minimum_hits),
    )


def front_tof_corridors_from_points(
    points_xyz: Iterable[tuple],
    front_half_angle_rad: float,
    center_half_angle_rad: float,
    minimum_height_m: float,
    maximum_height_m: float,
    minimum_hits: int = 2,
    path_half_width_m: float = 0.0,
) -> FrontTofClearance:
    """Split one ToF cloud into left/centre/right clearances by bearing.

    Mirrors lidar_corridors_from_points so the two channels can be compared
    on the same terms. Points arrive already in ``base_link``.
    """
    left = []
    center = []
    right = []
    path = []
    for x, y, z in points_xyz:
        if not all(math.isfinite(value) for value in (x, y, z)):
            continue
        if x <= 0.0 or not minimum_height_m <= z <= maximum_height_m:
            continue
        distance = math.hypot(x, y)
        # 통로 판정은 각도가 아니라 **가로 오프셋**이다. 각도로 보면 가까울수록
        # 통로가 넓어져서, 바로 옆에 있는 것이 정면으로 읽힌다.
        if path_half_width_m > 0.0 and abs(y) <= path_half_width_m:
            path.append(distance)
        angle = math.atan2(y, x)
        if abs(angle) > front_half_angle_rad:
            continue
        if abs(angle) <= center_half_angle_rad:
            center.append(distance)
        elif angle > 0.0:
            left.append(distance)
        else:
            right.append(distance)
    return FrontTofClearance(
        front_left_m=robust_nearest(left, minimum_hits),
        front_right_m=robust_nearest(right, minimum_hits),
        front_center_m=robust_nearest(center, minimum_hits),
        front_path_m=robust_nearest(path, minimum_hits),
    )


def front_tof_distance_from_points(
    points_xyz: Iterable[tuple],
    front_half_angle_rad: float,
    minimum_height_m: float,
    maximum_height_m: float,
    minimum_hits: int = 2,
) -> float:
    """Get one front clearance from a forward-facing ToF point cloud.

    Points are already transformed to ``base_link``.  Height filtering removes
    the floor, and the forward cone prevents returns outside the fork path
    from triggering the blind-spot channel.
    """
    distances = []
    for x, y, z in points_xyz:
        if not all(math.isfinite(value) for value in (x, y, z)):
            continue
        if x <= 0.0 or not minimum_height_m <= z <= maximum_height_m:
            continue
        if abs(math.atan2(y, x)) > front_half_angle_rad:
            continue
        distances.append(math.hypot(x, y))
    return robust_nearest(distances, minimum_hits)


def _dynamic_stop(
    detections: Sequence[VisionDetection],
    config: AvoidanceConfig,
) -> Optional[AvoidanceDecision]:
    labels = {label.lower() for label in config.dynamic_labels}
    for detection in detections:
        label = detection.label.strip().lower()
        if (
            label in labels
            and detection.confidence >= config.vision_confidence_threshold
            and (
                detection.distance_m is None
                or detection.distance_m
                <= config.dynamic_object_stop_distance_m
            )
        ):
            distance = (
                "unknown"
                if detection.distance_m is None
                else f"{detection.distance_m:.2f}m"
            )
            return AvoidanceDecision(
                AvoidanceAction.STOP,
                0.0,
                0.0,
                f"dynamic object {label} at {distance}",
            )
    return None


def _choose_turn(
    lidar: LidarCorridors,
    tof: FrontTofClearance,
    config: AvoidanceConfig,
) -> tuple:
    """Return ``(action, yaw_bias, reason)`` or no-turn values.

    The front-left/right ToFs nominate the direction away from the blocked
    fork side.  Roof LiDAR must confirm that the nominated corridor is clear.
    """
    delta = tof.front_left_m - tof.front_right_m
    preferred = None
    source = ""
    if delta < -config.tof_imbalance_m:
        preferred = AvoidanceAction.AVOID_RIGHT
        source = "front-left ToF blocked"
    elif delta > config.tof_imbalance_m:
        preferred = AvoidanceAction.AVOID_LEFT
        source = "front-right ToF blocked"
    else:
        lidar_delta = lidar.left_m - lidar.right_m
        if lidar_delta > config.lidar_clearance_margin_m:
            preferred = AvoidanceAction.AVOID_LEFT
            source = "roof LiDAR left corridor clearer"
        elif lidar_delta < -config.lidar_clearance_margin_m:
            preferred = AvoidanceAction.AVOID_RIGHT
            source = "roof LiDAR right corridor clearer"

    if preferred == AvoidanceAction.AVOID_LEFT:
        if lidar.left_m < config.minimum_turn_clearance_m:
            return AvoidanceAction.SLOW, 0.0, "left turn corridor blocked"
        return preferred, config.avoidance_yaw_rate_rps, source
    if preferred == AvoidanceAction.AVOID_RIGHT:
        if lidar.right_m < config.minimum_turn_clearance_m:
            return AvoidanceAction.SLOW, 0.0, "right turn corridor blocked"
        return preferred, -config.avoidance_yaw_rate_rps, source
    return AvoidanceAction.SLOW, 0.0, "both front ToFs similarly occupied"


def decide_avoidance(
    lidar: LidarCorridors,
    tof: FrontTofClearance,
    detections: Sequence[VisionDetection],
    missing_required_sensors: Sequence[str],
    config: AvoidanceConfig,
) -> AvoidanceDecision:
    config.validate()
    if missing_required_sensors:
        names = ",".join(sorted(missing_required_sensors))
        return AvoidanceDecision(
            AvoidanceAction.SENSOR_TIMEOUT,
            0.0,
            0.0,
            f"required sensor stale: {names}",
        )

    dynamic = _dynamic_stop(detections, config)
    if dynamic is not None:
        return dynamic

    # LiDAR covers tall/side structure.  ToFs cover the low frontal area that
    # the roof scan passes over because the fork sits below its scan plane.
    # ⚠️ **차체가 지나갈 통로 안의 것만 정지에 쓴다.**
    #
    # 방위 구간(좌/중/우)을 전부 넣으면 반대 문제가 난다: 12~35° 구간은 통로
    # 밖이 대부분이라 **옆에 있는 것에 멈춘다.** 0.27 m 거리에서 차체 반폭이
    # 차지하는 각은 15.3° 뿐이다. 모서리에 세워 두면 옆벽을 정면 장애물로 읽어
    # 앞이 비어 있어도 못 나갔다.
    #
    # 좌/우 구간은 회전 방향을 고를 때 쓴다(_choose_turn). 거기서는 "어느 쪽이
    # 더 비었나" 를 묻는 것이라 통로 밖도 의미가 있다.
    #
    nearest_front = min(lidar.center_m, tof.front_path_m)
    if nearest_front <= config.stop_distance_m:
        return AvoidanceDecision(
            AvoidanceAction.STOP,
            0.0,
            0.0,
            f"front obstacle at {nearest_front:.2f}m",
        )
    if nearest_front >= config.slowdown_distance_m:
        return AvoidanceDecision(
            AvoidanceAction.CLEAR,
            1.0,
            0.0,
            "roof and fork-level front corridors clear",
        )

    span = config.slowdown_distance_m - config.stop_distance_m
    scale = (nearest_front - config.stop_distance_m) / span
    scale = max(config.minimum_speed_scale, min(1.0, scale))
    if nearest_front > config.avoidance_engage_distance_m:
        # Slow down and leave the steering alone. There is still room for the
        # planner to work, and it can see around corners the guard cannot.
        return AvoidanceDecision(
            AvoidanceAction.SLOW,
            scale,
            0.0,
            f"front obstacle at {nearest_front:.2f}m, planner still steering",
        )
    action, yaw_bias, reason = _choose_turn(lidar, tof, config)
    return AvoidanceDecision(action, scale, yaw_bias, reason)


def apply_decision(
    linear_x: float,
    angular_z: float,
    decision: AvoidanceDecision,
    max_abs_yaw_rate_rps: float,
) -> tuple:
    if decision.action in {
        AvoidanceAction.STOP,
        AvoidanceAction.SENSOR_TIMEOUT,
    }:
        return 0.0, 0.0
    if linear_x <= 0.0:
        return linear_x, angular_z
    safe_linear = linear_x * decision.speed_scale
    safe_angular = angular_z + decision.yaw_bias_rps
    safe_angular = max(
        -max_abs_yaw_rate_rps,
        min(max_abs_yaw_rate_rps, safe_angular),
    )
    return safe_linear, safe_angular
