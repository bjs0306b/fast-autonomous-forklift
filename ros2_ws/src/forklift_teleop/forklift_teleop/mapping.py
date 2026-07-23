"""Open-loop cmd_vel mapping for the rear-steered forklift tele-op gate."""

from dataclasses import dataclass


@dataclass(frozen=True)
class ActuatorCommand:
    drive_percent: int
    steering_cdeg: int


@dataclass(frozen=True)
class TeleopLimits:
    max_linear_mps: float = 0.20
    max_angular_rps: float = 0.35
    linear_deadband_mps: float = 0.01
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
        if not 0 <= self.min_drive_percent <= self.max_drive_percent <= 60:
            raise ValueError("drive percentage limits are invalid")
        if not (
            8500 <= self.steering_min_cdeg
            <= self.steering_center_cdeg
            <= self.steering_max_cdeg
            <= 11500
        ):
            raise ValueError("steering limits are invalid")


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(value, maximum))


def map_twist(
    linear_x: float,
    angular_z: float,
    limits: TeleopLimits = TeleopLimits(),
) -> ActuatorCommand:
    """Map Twist values to signed PWM and a rear-steering servo command.

    This is intentionally an open-loop sign/response mapping, not the final
    Ackermann curvature model.  The angular/linear sign ratio makes rear
    steering reverse when the vehicle direction reverses.
    """
    limits.validate()

    if abs(linear_x) < limits.linear_deadband_mps:
        return ActuatorCommand(0, limits.steering_center_cdeg)

    speed_ratio = _clamp(
        abs(linear_x) / limits.max_linear_mps,
        0.0,
        1.0,
    )
    drive_magnitude = round(
        limits.min_drive_percent
        + speed_ratio * (limits.max_drive_percent - limits.min_drive_percent)
    )
    drive_percent = drive_magnitude if linear_x > 0.0 else -drive_magnitude

    if abs(angular_z) < 1e-6:
        return ActuatorCommand(drive_percent, limits.steering_center_cdeg)

    turn_ratio = _clamp(
        abs(angular_z) / limits.max_angular_rps,
        0.0,
        1.0,
    )
    steering_sign = 1 if (angular_z / linear_x) > 0.0 else -1

    if steering_sign > 0:
        steering_span = (
            limits.steering_max_cdeg - limits.steering_center_cdeg
        )
    else:
        steering_span = (
            limits.steering_center_cdeg - limits.steering_min_cdeg
        )

    steering_cdeg = (
        limits.steering_center_cdeg
        + steering_sign * round(turn_ratio * steering_span)
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
