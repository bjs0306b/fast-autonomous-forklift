"""ROS-independent transformations for Orin MQTT telemetry."""

import math
from typing import Optional


SCALE = 10.0
VALID_STATES = {
    "IDLE", "MOVING", "LOADING", "UNLOADING", "LIFTING", "LOWERING",
    "HOLDING", "ESTOPPED", "ERROR",
}


def normalize_angle(angle: float) -> float:
    return math.atan2(math.sin(angle), math.cos(angle))


def yaw_from_quaternion(x: float, y: float, z: float, w: float) -> float:
    return math.atan2(2.0 * (w * z + x * y),
                      1.0 - 2.0 * (y * y + z * z))


def align_slam_pose(
    x: float,
    y: float,
    yaw: float,
    origin_x: float,
    origin_y: float,
    origin_yaw: float,
) -> tuple[float, float, float]:
    """Place a SLAM-local pose in the warehouse lower-left coordinate frame."""
    cosine = math.cos(origin_yaw)
    sine = math.sin(origin_yaw)
    return (
        origin_x + cosine * x - sine * y,
        origin_y + sine * x + cosine * y,
        normalize_angle(yaw + origin_yaw),
    )


def telemetry_payload(
    *, vehicle_id: str, timestamp_ms: int, x_m: float, y_m: float,
    yaw: float, linear_mps: float, angular_rps: float, fork_height_m: float,
    loaded: bool, cargo_id: Optional[str], state: str,
    task_id: Optional[str], battery: float,
) -> dict:
    """Build the exact scaled payload while keeping angles unscaled."""
    if state not in VALID_STATES:
        raise ValueError(f"unsupported vehicle state: {state}")
    numeric = (
        x_m, y_m, yaw, linear_mps, angular_rps, fork_height_m, battery
    )
    if not all(math.isfinite(float(value)) for value in numeric):
        raise ValueError("telemetry numeric fields must be finite")
    if not 0.0 <= float(battery) <= 100.0:
        raise ValueError("battery must be between 0 and 100")
    return {
        "vehicleId": vehicle_id,
        "ts": int(timestamp_ms),
        "pose": {
            "x": round(x_m * SCALE, 3),
            "y": round(y_m * SCALE, 3),
            "yaw": round(normalize_angle(yaw), 4),
        },
        "velocity": {
            "linear": round(linear_mps * SCALE, 3),
            "angular": round(angular_rps, 3),
        },
        "forkHeight": round(fork_height_m * SCALE, 3),
        "loaded": bool(loaded),
        "cargoId": cargo_id,
        "cargo": None,
        "state": state,
        "taskId": task_id,
        "battery": round(float(battery), 1),
    }
