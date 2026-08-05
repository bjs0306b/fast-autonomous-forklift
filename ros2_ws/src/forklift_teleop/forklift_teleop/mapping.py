"""Convert cmd_vel into drive PWM and rear-steering servo commands."""

from dataclasses import dataclass
import math


@dataclass(frozen=True)
class ActuatorCommand:
    drive_percent: int
    steering_cdeg: int


@dataclass(frozen=True)
class TeleopLimits:
    max_linear_mps: float = 0.20
    max_angular_rps: float = 0.35
    linear_deadband_mps: float = 0.01
    wheelbase_m: float = 0.144
    # ⚠️ 이 값은 아래 steering_min/max 로 표현 가능한 각도와 **같아야 한다**
    #    (중립 9000 ± 3600 cdeg = ±36°). 어긋나면 turn_ratio 1.0 이 다른 각도를
    #    뜻하게 돼 "최대 조향" 이 실제 최대가 아니게 된다.
    rear_steering_limit_deg: float = 36.0
    # 2026-08-04 실측으로 50 → 35 (S15P11A304-198). 근거·측정표는
    # config/teleop.yaml 주석에 있다 — 여기 옮겨 적으면 갈라진다.
    min_drive_percent: int = 35
    max_drive_percent: int = 60
    # ⚠️ **후진은 상한이 다르다** (2026-08-05, S15P11A304-152).
    #
    # 실측: 같은 60% 로 전진 0.178 m/s · 후진 0.033 m/s — **19%** 다. 바닥을 바꿔도
    # 같아서 구조에서 오는 차이로 확인됐다. 뒷바퀴 조향차는 후진할 때 뒷바퀴가
    # **앞장서서**(leading) 바닥을 파고들고, 전진에서는 끌려온다(trailing).
    #
    # ⚠️ **전진 상한은 안 올린다.** 오늘 실측한 INSERT_SPEED_ACTUAL · 진입 깊이 ·
    #    조향 중립이 전부 전진 60% 기준이라 같이 올리면 그 값들이 무효가 된다.
    max_drive_percent_reverse: int = 100
    # ⚠️ **이 기본값들은 계속 낡은 채 방치되는 자리다.** 2026-08-04 오후까지
    #    10000/8500/11500(중립 ±15°) 이었고, 197·198 로 실제 설정이 두 번 바뀌는
    #    동안 여기만 안 따라왔다. 2026-08-05 에 또 한 번 그랬다.
    #
    #    브리지는 항상 teleop.yaml 을 명시적으로 넘기므로 **동작에는 영향이 없다.**
    #    그래서 아무도 안 본다. 인자 없이 `TeleopLimits()` 를 만드는 코드가 하나
    #    생기는 순간 **조용히 틀린 조향값**을 쓰게 된다.
    #
    # 2026-08-05 (S15P11A304-152): 9400/6600/12200 → 9000/5400/12600.
    #   중립 9000 은 펌웨어 펄스 범위(500~2500) 수정 후 실주행으로 다시 잡은 값이고,
    #   ±3600 은 그 뒤 실물에서 확인한 가동 범위다. 근거·측정표는 config/teleop.yaml
    #   주석에 있다 — 여기 옮겨 적으면 갈라진다.
    steering_center_cdeg: int = 9000
    steering_min_cdeg: int = 5400
    steering_max_cdeg: int = 12600

    def validate(self) -> None:
        if self.max_linear_mps <= 0.0 or self.max_angular_rps <= 0.0:
            raise ValueError("maximum velocities must be positive")
        if not 0 <= self.linear_deadband_mps < self.max_linear_mps:
            raise ValueError("linear deadband is invalid")
        if self.wheelbase_m <= 0.0:
            raise ValueError("wheelbase must be positive")
        if not 0.0 < self.rear_steering_limit_deg < 89.0:
            raise ValueError("rear steering limit is invalid")
        # 상한은 펌웨어 TELEOP_MAX_DRIVE_PERCENT · protocol.DRIVE_PERCENT_LIMIT 과
        # 같은 100 이다. 그보다 좁게 두면 여기서 막혀 후진 힘을 못 쓴다.
        if not 0 <= self.min_drive_percent <= self.max_drive_percent <= 100:
            raise ValueError("drive percentage limits are invalid")
        if not self.min_drive_percent <= self.max_drive_percent_reverse <= 100:
            raise ValueError("reverse drive percentage limit is invalid")
        # 서보 물리 안전 범위(펌웨어 config.h: SERVO_MIN/MAX_ANGLE_DEG = 30~150°).
        # cdeg = 도 × 100 이므로 3000~15000.
        #
        # ⚠️ 종전에는 8500~11500 으로 **훨씬 좁게** 박혀 있었다. 그 값은 "서보
        #    원점이 곧 기구 직진" 이라는 가정에서 나온 것인데, 실제로는 혼이 약 15°
        #    틀어져 끼워져 있어 **물리 직진이 11500(상한)** 이었다. 그래서 오른쪽
        #    조향을 표현할 수가 없었다 — 검증이 그 범위를 막고 있었기 때문이다
        #    (2026-08-04, S15P11A304-197).
        #
        # 좁은 상수로 두 번 막을 이유가 없다. 서보 보호는 펌웨어가 한다.
        if not (
            3000 <= self.steering_min_cdeg
            <= self.steering_center_cdeg
            <= self.steering_max_cdeg
            <= 15000
        ):
            raise ValueError(
                "steering limits are invalid: "
                f"min={self.steering_min_cdeg} center={self.steering_center_cdeg} "
                f"max={self.steering_max_cdeg} (허용 3000~15000, min≤center≤max)")


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(value, maximum))


def map_twist(
    linear_x: float,
    angular_z: float,
    limits: TeleopLimits = TeleopLimits(),
) -> ActuatorCommand:
    """Map Twist values to signed PWM and a rear-steering servo command.

    Positive rear-wheel angle points left. For a rear-steered vehicle,
    delta_rear = -atan(wheelbase * angular_z / linear_x). The installed
    linkage maps negative rear-wheel angle toward the larger servo command.
    """
    limits.validate()

    if abs(linear_x) < limits.linear_deadband_mps:
        return ActuatorCommand(0, limits.steering_center_cdeg)

    bounded_linear_x = math.copysign(
        min(abs(linear_x), limits.max_linear_mps),
        linear_x,
    )
    speed_ratio = _clamp(
        abs(bounded_linear_x) / limits.max_linear_mps,
        0.0,
        1.0,
    )
    # 후진은 상한이 다르다 — 같은 듀티로 훨씬 덜 나간다(TeleopLimits 주석 참조).
    max_percent = (limits.max_drive_percent if bounded_linear_x > 0.0
                   else limits.max_drive_percent_reverse)
    drive_magnitude = round(
        limits.min_drive_percent
        + speed_ratio * (max_percent - limits.min_drive_percent)
    )
    drive_percent = (
        drive_magnitude if bounded_linear_x > 0.0 else -drive_magnitude
    )

    bounded_angular_z = _clamp(
        angular_z,
        -limits.max_angular_rps,
        limits.max_angular_rps,
    )
    if abs(bounded_angular_z) < 1e-6:
        return ActuatorCommand(drive_percent, limits.steering_center_cdeg)

    curvature = bounded_angular_z / bounded_linear_x
    rear_steering_angle_rad = -math.atan(limits.wheelbase_m * curvature)
    steering_limit_rad = math.radians(limits.rear_steering_limit_deg)
    turn_ratio = _clamp(
        abs(rear_steering_angle_rad) / steering_limit_rad,
        0.0,
        1.0,
    )
    servo_direction = -1 if rear_steering_angle_rad > 0.0 else 1

    if servo_direction > 0:
        steering_span = (
            limits.steering_max_cdeg - limits.steering_center_cdeg
        )
    else:
        steering_span = (
            limits.steering_center_cdeg - limits.steering_min_cdeg
        )

    steering_cdeg = (
        limits.steering_center_cdeg
        + servo_direction * round(turn_ratio * steering_span)
    )
    return ActuatorCommand(drive_percent, steering_cdeg)


def select_command(
    linear_x: float,
    angular_z: float,
    command_age_sec: float,
    timeout_sec: float,
    limits: TeleopLimits = TeleopLimits(),
) -> ActuatorCommand:
    """Return a centered stop when the latest Twist is stale."""
    if timeout_sec <= 0.0:
        raise ValueError("command timeout must be positive")
    if command_age_sec > timeout_sec:
        return map_twist(0.0, 0.0, limits)
    return map_twist(linear_x, angular_z, limits)
