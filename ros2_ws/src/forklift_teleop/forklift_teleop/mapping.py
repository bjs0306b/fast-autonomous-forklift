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
    rear_steering_limit_deg: float = 15.0
    min_drive_percent: int = 50
    max_drive_percent: int = 60
    steering_center_cdeg: int = 10000
    steering_min_cdeg: int = 8500
    steering_max_cdeg: int = 11500

    def validate(self) -> None:
        if self.max_linear_mps <= 0.0 or self.max_angular_rps <= 0.0:
            raise ValueError("maximum velocities must be positive")
        if not 0 <= self.linear_deadband_mps < self.max_linear_mps:
            raise ValueError("linear deadband is invalid")
        if self.wheelbase_m <= 0.0:
            raise ValueError("wheelbase must be positive")
        if not 0.0 < self.rear_steering_limit_deg < 89.0:
            raise ValueError("rear steering limit is invalid")
        if not 0 <= self.min_drive_percent <= self.max_drive_percent <= 60:
            raise ValueError("drive percentage limits are invalid")
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
    drive_magnitude = round(
        limits.min_drive_percent
        + speed_ratio * (limits.max_drive_percent - limits.min_drive_percent)
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
