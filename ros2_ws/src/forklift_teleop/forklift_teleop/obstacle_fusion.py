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


@dataclass(frozen=True)
class FrontTofClearance:
    """Clearance along each fork-side forward blind spot."""

    front_left_m: float = math.inf
    front_right_m: float = math.inf


@dataclass(frozen=True)
class VisionDetection:
    label: str
    confidence: float
    distance_m: Optional[float] = None


@dataclass(frozen=True)
class AvoidanceConfig:
    stop_distance_m: float = 0.45
    slowdown_distance_m: float = 1.00
    minimum_speed_scale: float = 0.25
    avoidance_yaw_rate_rps: float = 0.18
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
        if self.minimum_turn_clearance_m <= self.stop_distance_m:
            raise ValueError(
                "minimum_turn_clearance_m must exceed stop_distance_m"
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
    for x, y in points_xy:
        if not (math.isfinite(x) and math.isfinite(y)):
            continue
        distance = math.hypot(x, y)
        if distance <= 0.0:
            continue
        angle = math.atan2(y, x)
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
    nearest_front = min(
        lidar.center_m,
        tof.front_left_m,
        tof.front_right_m,
    )
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
