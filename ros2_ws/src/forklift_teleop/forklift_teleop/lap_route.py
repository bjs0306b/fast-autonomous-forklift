"""Coordinate conversion helpers for the physical warehouse lap."""

import math


def normalize_angle(angle: float) -> float:
    return math.atan2(math.sin(angle), math.cos(angle))


def warehouse_to_slam(
    x_m: float,
    y_m: float,
    yaw_rad: float,
    origin_x_m: float,
    origin_y_m: float,
    origin_yaw_rad: float,
) -> tuple[float, float, float]:
    """Convert warehouse lower-left coordinates to the fresh SLAM map frame.

    A new slam_toolbox map places the vehicle at (0, 0, 0). ``origin_*`` is
    that same starting vehicle pose measured in the warehouse frame.
    """
    dx = x_m - origin_x_m
    dy = y_m - origin_y_m
    cosine = math.cos(origin_yaw_rad)
    sine = math.sin(origin_yaw_rad)
    return (
        cosine * dx + sine * dy,
        -sine * dx + cosine * dy,
        normalize_angle(yaw_rad - origin_yaw_rad),
    )


def subdivide_route(
    start: tuple[float, float, float],
    waypoints: list[tuple[float, float, float]],
    max_segment_length_m: float,
) -> list[tuple[float, float, float]]:
    """Split long legs so a fresh SLAM map can grow ahead of Nav2.

    Nav2 cannot plan outside the current occupancy-grid bounds, even when its
    planner allows unknown cells.  A fresh online map is therefore explored
    with short goals.  Intermediate goals use the leg's final heading so the
    forklift starts each corner in the intended travel direction.
    """
    if not math.isfinite(max_segment_length_m) or max_segment_length_m <= 0.0:
        raise ValueError("max segment length must be positive and finite")

    result = []
    previous_x, previous_y, _ = start
    for target_x, target_y, target_yaw in waypoints:
        distance = math.hypot(target_x - previous_x, target_y - previous_y)
        segment_count = max(1, math.ceil(distance / max_segment_length_m))
        for index in range(1, segment_count + 1):
            ratio = index / segment_count
            result.append((
                previous_x + (target_x - previous_x) * ratio,
                previous_y + (target_y - previous_y) * ratio,
                target_yaw,
            ))
        previous_x, previous_y = target_x, target_y
    return result
