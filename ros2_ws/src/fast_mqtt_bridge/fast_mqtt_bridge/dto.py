"""Backend-compatible MQTT DTOs and geometry conversions."""

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import math
from typing import Any, Mapping, Optional, Sequence


KST = timezone(timedelta(hours=9))
ALLOWED_STATUSES = frozenset(
    {
        "UNKNOWN",
        "IDLE",
        "ACTIVE",
        "MOVING",
        "LIFTING",
        "LOADING",
        "UNLOADING",
        "ESTOP",
        "ERROR",
        "OFFLINE",
    }
)
ALLOWED_FRAMES = frozenset({"map", "odom"})
RESULT_STATUSES = frozenset(
    {"ACCEPTED", "IN_PROGRESS", "SUCCESS", "FAILED", "REJECTED", "CANCELLED"}
)


def now_kst() -> str:
    return datetime.now(KST).isoformat(timespec="milliseconds")


def normalize_heading(value: float) -> float:
    if not math.isfinite(value):
        raise ValueError("heading must be finite")
    normalized = value % 360.0
    return 0.0 if normalized == 360.0 else normalized


def quaternion_to_heading(x: float, y: float, z: float, w: float) -> float:
    if not all(math.isfinite(value) for value in (x, y, z, w)):
        raise ValueError("quaternion must contain finite values")
    yaw = math.atan2(
        2.0 * (w * z + x * y),
        1.0 - 2.0 * (y * y + z * z),
    )
    return normalize_heading(math.degrees(yaw))


def _frame(value: Optional[str]) -> str:
    frame_id = value or "map"
    if frame_id not in ALLOWED_FRAMES:
        raise ValueError("frameId must be map or odom")
    return frame_id


def _json(data: Mapping[str, Any]) -> str:
    return json.dumps(data, ensure_ascii=False, separators=(",", ":"))


@dataclass(frozen=True)
class StatusMessage:
    forklift_id: str
    status: str
    battery: float
    timestamp: str

    @classmethod
    def create(
        cls,
        forklift_id: str,
        status: str,
        battery: float,
        timestamp: Optional[str] = None,
    ) -> "StatusMessage":
        normalized = status.strip().upper()
        if normalized not in ALLOWED_STATUSES:
            normalized = "UNKNOWN"
        if not math.isfinite(battery) or not 0.0 <= battery <= 100.0:
            raise ValueError("battery must be finite and between 0 and 100")
        if not float(battery).is_integer():
            raise ValueError("current backend ForkliftStatusMessage requires integer battery")
        return cls(forklift_id, normalized, int(battery), timestamp or now_kst())

    def to_dict(self) -> dict[str, Any]:
        return {
            "forkliftId": self.forklift_id,
            "status": self.status,
            "battery": self.battery,
            "timestamp": self.timestamp,
        }

    def to_json(self) -> str:
        return _json(self.to_dict())


@dataclass(frozen=True)
class LocationMessage:
    vehicle_id: str
    x: float
    y: float
    heading: float
    frame_id: str
    message_at: str

    @classmethod
    def create(
        cls,
        vehicle_id: str,
        x: float,
        y: float,
        heading: float,
        frame_id: Optional[str] = None,
        message_at: Optional[str] = None,
    ) -> "LocationMessage":
        if not math.isfinite(x) or not math.isfinite(y):
            raise ValueError("location coordinates must be finite")
        return cls(
            vehicle_id,
            x,
            y,
            normalize_heading(heading),
            _frame(frame_id),
            message_at or now_kst(),
        )

    def to_dict(self) -> dict[str, Any]:
        # Current Spring DTO requires nested position and messageAt.
        return {
            "vehicleId": self.vehicle_id,
            "position": {
                "x": self.x,
                "y": self.y,
                "frameId": self.frame_id,
            },
            "heading": self.heading,
            "messageAt": self.message_at,
        }

    def to_json(self) -> str:
        return _json(self.to_dict())


@dataclass(frozen=True)
class PathPoint:
    x: float
    y: float
    heading: float

    def to_dict(self) -> dict[str, float]:
        return {"x": self.x, "y": self.y, "heading": self.heading}


@dataclass(frozen=True)
class PathMessage:
    vehicle_id: str
    frame_id: str
    path: tuple[PathPoint, ...]
    timestamp: str

    @classmethod
    def create(
        cls,
        vehicle_id: str,
        frame_id: Optional[str],
        points: Sequence[tuple[float, float, float]],
        timestamp: Optional[str] = None,
        max_points: int = 1000,
    ) -> "PathMessage":
        if not points:
            raise ValueError("empty path is not published because backend requires a goal")
        if len(points) > max_points:
            raise ValueError(f"path exceeds {max_points} points")
        converted = []
        for x, y, heading in points:
            if not math.isfinite(x) or not math.isfinite(y):
                raise ValueError("path coordinates must be finite")
            converted.append(PathPoint(x, y, normalize_heading(heading)))
        return cls(vehicle_id, _frame(frame_id), tuple(converted), timestamp or now_kst())

    def to_dict(self) -> dict[str, Any]:
        # Current backend routes path through IsaacForkliftPathMessage.
        goal = self.path[-1]
        return {
            "forkliftId": self.vehicle_id,
            "waypoints": [
                {"x": point.x, "y": point.y}
                for point in self.path[:-1]
            ],
            "goal": goal.to_dict(),
            "timestamp": self.timestamp,
        }

    def to_json(self) -> str:
        return _json(self.to_dict())

    def fingerprint(self) -> str:
        return _json(
            {
                "vehicleId": self.vehicle_id,
                "frameId": self.frame_id,
                "path": [point.to_dict() for point in self.path],
            }
        )


@dataclass(frozen=True)
class Destination:
    x: Any
    y: Any
    heading: Any
    frame_id: Any


@dataclass(frozen=True)
class CommandMessage:
    command_id: str
    vehicle_id: str
    target_system: str
    command_category: str
    command: str
    destination: Optional[Destination]
    reason: Optional[str]

    @classmethod
    def from_json(cls, payload: str | bytes) -> "CommandMessage":
        raw = json.loads(payload)
        if not isinstance(raw, dict):
            raise ValueError("command payload must be a JSON object")
        command_payload = raw.get("payload")
        if command_payload is None:
            command_payload = {}
        elif not isinstance(command_payload, dict):
            raise ValueError("payload must be an object")
        destination_raw = command_payload.get("destination")
        destination = None
        if destination_raw is not None:
            if not isinstance(destination_raw, dict):
                raise ValueError("payload.destination must be an object")
            destination = Destination(
                x=destination_raw.get("x"),
                y=destination_raw.get("y"),
                heading=destination_raw.get("heading"),
                frame_id=destination_raw.get("frameId") or "map",
            )
        return cls(
            command_id=str(raw.get("commandId") or ""),
            vehicle_id=str(raw.get("vehicleId") or ""),
            target_system=str(raw.get("targetSystem") or "").upper(),
            command_category=str(raw.get("commandCategory") or "").upper(),
            command=str(raw.get("command") or "").upper(),
            destination=destination,
            reason=raw.get("reason"),
        )


@dataclass(frozen=True)
class CommandResult:
    command_id: str
    vehicle_id: str
    target_system: str
    command_category: str
    command: str
    result: str
    message: str
    completed_at: str

    @classmethod
    def create(
        cls,
        command: CommandMessage,
        result: str,
        message: str,
        completed_at: Optional[str] = None,
    ) -> "CommandResult":
        normalized = result.upper()
        if normalized not in RESULT_STATUSES:
            raise ValueError("unsupported backend command result")
        return cls(
            command.command_id,
            command.vehicle_id,
            command.target_system,
            command.command_category,
            command.command,
            normalized,
            message,
            completed_at or now_kst(),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "commandId": self.command_id,
            "vehicleId": self.vehicle_id,
            "targetSystem": self.target_system,
            "commandCategory": self.command_category,
            "command": self.command,
            "result": self.result,
            "message": self.message,
            "completedAt": self.completed_at,
        }

    def to_json(self) -> str:
        return _json(self.to_dict())
