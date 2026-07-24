"""Thread-safe command validation, deduplication, and adapter dispatch."""

from collections import OrderedDict
from dataclasses import dataclass
import math
from threading import Lock
import time
from typing import Callable, Optional, Protocol

from fast_mqtt_bridge.dto import CommandMessage, CommandResult


COMMAND_RULES = {
    "MOVE": ("ROS2", "MOVE", True),
    "STOP": ("EMBEDDED", "SAFETY", False),
    "FORK_UP": ("EMBEDDED", "FORK", False),
    "FORK_DOWN": ("EMBEDDED", "FORK", False),
    "LOAD": ("EMBEDDED", "LOAD", False),
    "UNLOAD": ("EMBEDDED", "LOAD", False),
    "EMERGENCY_STOP": ("ALL", "SAFETY", False),
    "RESET_ESTOP": ("ALL", "SAFETY", False),
}
TERMINAL_RESULTS = frozenset({"SUCCESS", "FAILED", "REJECTED", "CANCELLED"})
TRANSITIONS = {
    None: frozenset({"ACCEPTED", "FAILED", "REJECTED", "CANCELLED"}),
    "ACCEPTED": frozenset({"IN_PROGRESS", "SUCCESS", "FAILED", "REJECTED", "CANCELLED"}),
    "IN_PROGRESS": TERMINAL_RESULTS,
}


class CommandAdapter(Protocol):
    def execute(
        self,
        command: CommandMessage,
        emit_result: Callable[[str, str], None],
    ) -> None:
        """Begin execution and report backend-compatible result transitions."""


class UnavailableCommandAdapter:
    def execute(
        self,
        command: CommandMessage,
        emit_result: Callable[[str, str], None],
    ) -> None:
        emit_result("REJECTED", "ROS2 command interface is not configured; team confirmation required")


class RecentCommandCache:
    def __init__(
        self,
        ttl_seconds: float = 3600.0,
        max_entries: int = 1000,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if ttl_seconds <= 0 or max_entries <= 0:
            raise ValueError("cache limits must be positive")
        self._ttl = ttl_seconds
        self._max_entries = max_entries
        self._clock = clock
        self._entries: OrderedDict[str, tuple[float, Optional[CommandResult]]] = OrderedDict()
        self._lock = Lock()

    def _purge(self, now: float) -> None:
        expired = [
            key for key, (created_at, _) in self._entries.items()
            if now - created_at >= self._ttl
        ]
        for key in expired:
            self._entries.pop(key, None)

    def reserve(self, command_id: str) -> bool:
        now = self._clock()
        with self._lock:
            self._purge(now)
            if command_id in self._entries:
                return False
            self._entries[command_id] = (now, None)
            while len(self._entries) > self._max_entries:
                self._entries.popitem(last=False)
            return True

    def remember_result(self, result: CommandResult) -> None:
        now = self._clock()
        with self._lock:
            created_at = self._entries.get(result.command_id, (now, None))[0]
            self._entries[result.command_id] = (created_at, result)
            self._entries.move_to_end(result.command_id)

    def result_for(self, command_id: str) -> Optional[CommandResult]:
        now = self._clock()
        with self._lock:
            self._purge(now)
            entry = self._entries.get(command_id)
            return entry[1] if entry else None


class ResultStateTracker:
    def __init__(self) -> None:
        self._states: dict[str, str] = {}
        self._lock = Lock()

    def transition(self, command_id: str, next_result: str) -> bool:
        with self._lock:
            current = self._states.get(command_id)
            allowed = TRANSITIONS.get(current, frozenset())
            if next_result not in allowed:
                return False
            self._states[command_id] = next_result
            return True


@dataclass(frozen=True)
class ValidationFailure:
    result: str
    message: str


def validate_command(command: CommandMessage, vehicle_id: str) -> Optional[ValidationFailure]:
    if not command.command_id:
        return ValidationFailure("REJECTED", "commandId is required")
    if command.vehicle_id != vehicle_id:
        return ValidationFailure("REJECTED", "vehicleId mismatch")
    if command.target_system not in {"ROS2", "ALL"}:
        return ValidationFailure("REJECTED", "targetSystem is not handled by ROS2")
    rule = COMMAND_RULES.get(command.command)
    if rule is None:
        return ValidationFailure("REJECTED", "unsupported command")
    target, category, destination_required = rule
    if command.target_system != target or command.command_category != category:
        return ValidationFailure("REJECTED", "command target/category combination is invalid")
    if destination_required and command.destination is None:
        return ValidationFailure("REJECTED", "MOVE destination is required")
    if command.destination is not None:
        destination = command.destination
        try:
            values = (
                float(destination.x),
                float(destination.y),
                float(destination.heading),
            )
        except (TypeError, ValueError):
            return ValidationFailure(
                "REJECTED",
                "destination x, y and heading are required numbers",
            )
        if not all(math.isfinite(value) for value in values):
            return ValidationFailure("REJECTED", "destination values must be finite")
        if not 0.0 <= values[2] < 360.0:
            return ValidationFailure(
                "REJECTED",
                "destination heading must be in [0, 360)",
            )
        if destination.frame_id not in {"map", "odom"}:
            return ValidationFailure("REJECTED", "frameId must be map or odom")
    return None


class CommandProcessor:
    def __init__(
        self,
        vehicle_id: str,
        adapter: CommandAdapter,
        publish_result: Callable[[CommandResult], None],
        cache: Optional[RecentCommandCache] = None,
    ) -> None:
        self._vehicle_id = vehicle_id
        self._adapter = adapter
        self._publish_result = publish_result
        self._cache = cache or RecentCommandCache()
        self._states = ResultStateTracker()

    def process(self, command: CommandMessage) -> bool:
        failure = validate_command(command, self._vehicle_id)
        if failure is not None:
            # A missing commandId cannot be correlated and is logged only.
            if command.command_id:
                self._emit(command, failure.result, failure.message)
            return False
        if not self._cache.reserve(command.command_id):
            previous = self._cache.result_for(command.command_id)
            if previous is not None:
                self._publish_result(previous)
            return False

        try:
            self._adapter.execute(
                command,
                lambda result, message: self._emit(command, result, message),
            )
        except Exception as error:
            self._emit(command, "FAILED", f"ROS2 command adapter error: {error}")
            return False
        return True

    def cancel_pending(self, command: CommandMessage, message: str) -> bool:
        failure = validate_command(command, self._vehicle_id)
        if failure is not None or command.command != "MOVE":
            return False
        if not self._cache.reserve(command.command_id):
            return False
        self._emit(command, "CANCELLED", message)
        return True

    def _emit(self, command: CommandMessage, result: str, message: str) -> None:
        normalized = result.upper()
        if not self._states.transition(command.command_id, normalized):
            return
        command_result = CommandResult.create(command, normalized, message)
        if normalized in TERMINAL_RESULTS:
            self._cache.remember_result(command_result)
        self._publish_result(command_result)
