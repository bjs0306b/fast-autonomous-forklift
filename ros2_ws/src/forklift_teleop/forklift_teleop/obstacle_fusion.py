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
    # 후방 **옆** 구간. 정후방 콘(rear_m) 이 끝나는 각도에서 시작하는 고리다.
    #
    # ⚠️ 조향하며 후진할 때 꼬리가 쓸고 지나가는 곳이 여기다. 정후방 콘은
    #    ±18° 뿐인데 꼬리는 18°~42° 로 나가므로, 이 두 채널이 없으면 **아무도
    #    보지 않는 곳으로 차 뒤를 휘두르게 된다.** 곧게 후진할 때는 rear_m
    #    하나로 충분했기 때문에 종전에는 이 점들을 그냥 버렸다.
    rear_left_m: float = math.inf
    rear_right_m: float = math.inf


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
#
# ⚠️ **이 값은 후륜 36° 락 기준으로 유도됐다** (회전반경 0.198 m). 지금
#    teleop.yaml 의 rear_steering_limit_deg 는 58° 라 반경 0.090, 하한은
#    0.293 이 맞다. 즉 이 상수는 **과소값**이다. 조향 후진의 꼬리 여유를
#    여기서 가져오면 안 된다 -- 그쪽은 탈출 곡률(R 0.40)에서 따로 유도한다.
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
    # 조향하며 후진할 때 **꼬리가 나가는 쪽**에 요구하는 여유. 이보다 좁으면
    # 요만 0으로 만들고 직선 후진으로 저하시킨다(속도는 유지).
    #
    # unstick 자신의 임계(0.50)보다 **낮게** 둔다. 그래야 unstick 은 자기
    # 정책으로 먼저 판단하고, 여기는 unstick 이 아닌 출처(텔레옵·back_off.py·
    # 나중의 BT)가 낸 조향 후진만 걸러낸다.
    reverse_tail_clearance_m: float = 0.40
    # 꺾인 명령일수록 감속을 덜 한다. 0 이면 종전 그대로, 1 이면 최대 곡률에서
    # 감속을 전혀 안 한다. 근거는 steered_speed_scale 의 주석에 있다.
    steered_scale_relief: float = 0.0
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
            self.reverse_tail_clearance_m,
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
    rear_side_half_angle_rad: float = 0.0,
) -> LidarCorridors:
    """Split roof-LiDAR points into center, left-turn and right-turn space.

    ``rear_side_half_angle_rad`` 는 정후방 콘 **바깥쪽** 고리의 바깥 경계다
    (후방축 기준). **0 이면 꺼진다** -- 기존 호출자는 이 인자를 안 넘기므로
    종전 동작이 그대로 나온다.
    """
    left = []
    center = []
    right = []
    rear = []
    rear_left = []
    rear_right = []
    for x, y in points_xy:
        if not (math.isfinite(x) and math.isfinite(y)):
            continue
        distance = math.hypot(x, y)
        if distance <= 0.0:
            continue
        angle = math.atan2(y, x)
        if abs(abs(angle) - math.pi) <= center_half_angle_rad:
            rear.append(distance)
        # 정후방 콘이 끝나는 곳부터 시작하는 고리. 두 구간은 겹치지 않으므로
        # rear_m 은 이 분기와 무관하게 종전 값 그대로다.
        off_rear = math.pi - abs(angle)
        if center_half_angle_rad < off_rear <= rear_side_half_angle_rad:
            # angle > 0 은 y > 0, 즉 왼쪽이다(REP-103).
            (rear_left if angle > 0.0 else rear_right).append(distance)
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
        rear_left_m=robust_nearest(rear_left, minimum_hits),
        rear_right_m=robust_nearest(rear_right, minimum_hits),
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


def preferred_avoidance_side(
    lidar: LidarCorridors,
    tof: FrontTofClearance,
    tof_imbalance_m: float,
    lidar_clearance_margin_m: float,
) -> tuple:
    """어느 쪽이 더 트였는가. ``(action, source)`` 또는 ``(None, "")``.

    `_choose_turn` 의 앞부분을 그대로 들어낸 것이다. 여유 검사와 요 편향은
    호출자 몫이라, 탈출 후진처럼 **여유 기준이 다른 쪽**도 같은 판단을 쓸 수
    있다. 임계를 설정 객체가 아니라 스칼라로 받는 것도 그래서다.
    """
    delta = tof.front_left_m - tof.front_right_m
    if delta < -tof_imbalance_m:
        return AvoidanceAction.AVOID_RIGHT, "front-left ToF blocked"
    if delta > tof_imbalance_m:
        return AvoidanceAction.AVOID_LEFT, "front-right ToF blocked"
    lidar_delta = lidar.left_m - lidar.right_m
    if lidar_delta > lidar_clearance_margin_m:
        return AvoidanceAction.AVOID_LEFT, "roof LiDAR left corridor clearer"
    if lidar_delta < -lidar_clearance_margin_m:
        return AvoidanceAction.AVOID_RIGHT, "roof LiDAR right corridor clearer"
    return None, ""


def escape_yaw_sign(side) -> float:
    """가려던 쪽 -> 요레이트 부호. 없으면 0.0.

    ⚠️ **후진이라고 뒤집지 않는다.** `map_twist` 가 곡률을 `angular_z /
       linear_x` 로 내므로 `linear_x` 가 음수면 후륜각이 **저절로** 뒤집힌다.
       같은 요레이트 부호를 유지하는 것만으로 바퀴가 반대로 꺾이고 회전이
       누적된다. 여기서 한 번 더 뒤집으면 두 번 뒤집혀 전진 구간과 정확히
       상쇄되고, 차는 앞뒤로 흔들리기만 한다 -- `pivot_node` 가 같은 이유로
       두 구간에 같은 부호를 쓰고 `test_pivot` 이 그 버그를 이미 잡았다.
    """
    if side == AvoidanceAction.AVOID_LEFT:
        return 1.0
    if side == AvoidanceAction.AVOID_RIGHT:
        return -1.0
    return 0.0


def tail_swing_side_clearance(lidar: LidarCorridors, yaw_sign: float) -> float:
    """조향 후진에서 **꼬리가 나가는 쪽**의 여유.

    ⚠️ **도는 쪽이 아니라 반대쪽이다.** 순간회전중심이 `y = v / ω` 에 있어,
       후진(v<0)에 요가 양수(좌회전)면 중심이 오른쪽에 잡히고 꼬리는
       **오른쪽으로** 쓸린다. 차체 뒤끝은 x<0 이라 그 지점의 횡속도가
       `ω · x` 로 부호가 뒤집히기 때문이다.

       가장 뒤집어 쓰기 쉬운 곳이라 전용 테스트가 붙어 있다.
    """
    if yaw_sign > 0.0:
        return lidar.rear_right_m
    if yaw_sign < 0.0:
        return lidar.rear_left_m
    return math.inf


def limit_reverse_yaw(
    linear_x: float,
    angular_z: float,
    lidar: LidarCorridors,
    config: AvoidanceConfig,
) -> float:
    """꼬리가 나가는 쪽이 막혔으면 후진 요를 0으로. 속도는 안 건드린다.

    시스템의 **모든** 후진 명령에 걸리는 백스톱이다 -- 텔레옵이든
    `tools/back_off.py` 든 조향 후진을 내면 여기를 지난다.

    ⚠️ 저하는 언제나 **조향후진 -> 직선후진**이지 정지가 아니다. 앞이 막혀서
       물러나는 중인데 여기서 세우면 갇힌 채로 남는다.
    """
    if linear_x >= 0.0 or angular_z == 0.0:
        return angular_z
    sign = 1.0 if angular_z > 0.0 else -1.0
    if tail_swing_side_clearance(lidar, sign) < config.reverse_tail_clearance_m:
        return 0.0
    return angular_z


def _finite_or_none(value: float):
    return None if math.isinf(value) else round(value, 3)


def _distance_or_inf(value) -> float:
    return math.inf if value is None else float(value)


def status_document(
    action_value: str,
    reason: str,
    speed_scale: float,
    lidar: LidarCorridors,
    tof: FrontTofClearance,
    vision_labels: Sequence[str],
) -> dict:
    """`/obstacle_avoidance/status` 의 본문. 형식이 사는 유일한 곳이다.

    가드가 만들고 unstick 이 되읽으므로 한쪽만 바뀌면 조용히 어긋난다.
    `corridors_from_status` 와 왕복 테스트로 묶어 둔다.

    `None` 은 "무한" 이지 "모름" 이 아니다 -- 되읽는 쪽은 `inf` 로 되돌린다.
    """
    return {
        "action": action_value,
        "reason": reason,
        "speedScale": round(speed_scale, 3),
        "roofLidar": {
            "left": _finite_or_none(lidar.left_m),
            "center": _finite_or_none(lidar.center_m),
            "right": _finite_or_none(lidar.right_m),
            "rear": _finite_or_none(lidar.rear_m),
            # 조향 후진에서 꼬리가 쓸고 가는 고리. 정후방(rear)과 안 겹친다.
            "rearLeft": _finite_or_none(lidar.rear_left_m),
            "rearRight": _finite_or_none(lidar.rear_right_m),
        },
        # Bearing sectors, not sensors -- the two used to read almost
        # identically because each was one sensor's nearest hit.
        "frontTof": {
            "left": _finite_or_none(tof.front_left_m),
            "center": _finite_or_none(tof.front_center_m),
            "right": _finite_or_none(tof.front_right_m),
            # 정지 판정이 실제로 쓰는 값. 좌/우는 회전 방향 고를 때만 쓴다.
            "path": _finite_or_none(tof.front_path_m),
        },
        "visionLabels": list(vision_labels),
    }


def corridors_from_status(document: dict) -> tuple:
    """status 본문을 ``(LidarCorridors, FrontTofClearance)`` 로 되돌린다.

    빠진 키는 `inf` 로 읽는다. 옛 형식으로 만든 문서도 조용히 통과해야
    한다 -- 새 필드가 없다고 탈출이 죽으면 안 된다.
    """
    roof = document.get("roofLidar") or {}
    front = document.get("frontTof") or {}
    lidar = LidarCorridors(
        left_m=_distance_or_inf(roof.get("left")),
        center_m=_distance_or_inf(roof.get("center")),
        right_m=_distance_or_inf(roof.get("right")),
        rear_m=_distance_or_inf(roof.get("rear")),
        rear_left_m=_distance_or_inf(roof.get("rearLeft")),
        rear_right_m=_distance_or_inf(roof.get("rearRight")),
    )
    tof = FrontTofClearance(
        front_left_m=_distance_or_inf(front.get("left")),
        front_center_m=_distance_or_inf(front.get("center")),
        front_right_m=_distance_or_inf(front.get("right")),
        front_path_m=_distance_or_inf(front.get("path")),
    )
    return lidar, tof


def _choose_turn(
    lidar: LidarCorridors,
    tof: FrontTofClearance,
    config: AvoidanceConfig,
) -> tuple:
    """Return ``(action, yaw_bias, reason)`` or no-turn values.

    The front-left/right ToFs nominate the direction away from the blocked
    fork side.  Roof LiDAR must confirm that the nominated corridor is clear.
    """
    preferred, source = preferred_avoidance_side(
        lidar, tof, config.tof_imbalance_m, config.lidar_clearance_margin_m
    )

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


def steered_speed_scale(
    linear_x: float,
    angular_z: float,
    speed_scale: float,
    relief: float,
    full_relief_curvature: float,
) -> float:
    """꺾인 명령일수록 감속을 덜 한다. 반환값은 실제로 쓸 배율.

    ⚠️ **감속과 조향이 겹치면 차가 선다.** 곧게 갈 때 도는 듀티는 꺾인 상태를
       못 이긴다 -- 후륜이 옆으로 갈아내는 만큼 더 필요하다. 그런데 가드는
       장애물에 다가갈수록 속도를 줄이고, 회피는 바로 그때 꺾는다. 둘이 만나면
       "피하려고 꺾었는데 그 자리에 서는" 상태가 된다.

       그래서 곡률에 비례해 배율을 1.0 쪽으로 되돌린다. 감속의 목적은 제동거리
       확보인데, 어차피 못 움직이는 속도로 줄이는 것은 그 목적에도 안 맞는다.

    ``relief`` 0 이면 종전 그대로다. 1 이면 최대 곡률에서 감속을 전혀 안 한다.
    """
    if relief <= 0.0 or full_relief_curvature <= 0.0 or speed_scale >= 1.0:
        return speed_scale
    if abs(linear_x) < 1e-9:
        return speed_scale
    curvature = abs(angular_z) / abs(linear_x)
    fraction = min(1.0, curvature / full_relief_curvature) * min(1.0, relief)
    return speed_scale + (1.0 - speed_scale) * fraction


def apply_decision(
    linear_x: float,
    angular_z: float,
    decision: AvoidanceDecision,
    max_abs_yaw_rate_rps: float,
    steered_scale_relief: float = 0.0,
    full_relief_curvature: float = 0.0,
) -> tuple:
    if decision.action in {
        AvoidanceAction.STOP,
        AvoidanceAction.SENSOR_TIMEOUT,
    }:
        return 0.0, 0.0
    if linear_x <= 0.0:
        return linear_x, angular_z
    scale = steered_speed_scale(
        linear_x, angular_z, decision.speed_scale,
        steered_scale_relief, full_relief_curvature,
    )
    safe_linear = linear_x * scale
    safe_angular = angular_z + decision.yaw_bias_rps
    safe_angular = max(
        -max_abs_yaw_rate_rps,
        min(max_abs_yaw_rate_rps, safe_angular),
    )
    return safe_linear, safe_angular
