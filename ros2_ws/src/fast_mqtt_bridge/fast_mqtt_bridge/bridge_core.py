"""Dependency-free bridge orchestration used by the ROS2 node and tests."""

from hashlib import sha256
import time
from typing import Callable, Mapping, Optional

from fast_mqtt_bridge.command_handler import (
    CommandAdapter,
    CommandProcessor,
    RecentCommandCache,
)
from fast_mqtt_bridge.dto import (
    CommandMessage,
    CommandResult,
    LocationMessage,
    PathMessage,
    StatusMessage,
)


class BridgeCore:
    def __init__(
        self,
        vehicle_id: str,
        adapter: CommandAdapter,
        publish: Callable[[str, str], bool],
        location_interval_ms: int = 100,
        cache_ttl_sec: float = 3600.0,
        cache_max_entries: int = 1000,
        clock: Callable[[], float] = time.monotonic,
        topic_overrides: Optional[Mapping[str, str]] = None,
    ) -> None:
        self.vehicle_id = vehicle_id
        self.topics = {
            "status": f"forklift/{vehicle_id}/status",
            "location": f"forklift/{vehicle_id}/location",
            "path": f"forklift/{vehicle_id}/path",
            "command_result": f"forklift/{vehicle_id}/command-result",
        }
        if topic_overrides:
            self.topics.update(
                {key: value for key, value in topic_overrides.items() if value}
            )
        self._publish = publish
        self._location_interval = location_interval_ms / 1000.0
        self._clock = clock
        self._last_location_publish = float("-inf")
        self._last_status: Optional[StatusMessage] = None
        self._last_path_digest: Optional[str] = None
        self._processor = CommandProcessor(
            vehicle_id,
            adapter,
            self._publish_result,
            RecentCommandCache(cache_ttl_sec, cache_max_entries, clock),
        )

    def process_command(self, command: CommandMessage) -> bool:
        return self._processor.process(command)

    def cancel_pending_move(self, command: CommandMessage) -> bool:
        return self._processor.cancel_pending(
            command,
            "Cancelled by higher-priority EMERGENCY_STOP",
        )

    def _publish_result(self, result: CommandResult) -> None:
        self._publish(self.topics["command_result"], result.to_json())

    def publish_status(self, status: StatusMessage, force: bool = False) -> bool:
        changed = (
            self._last_status is None
            or self._last_status.status != status.status
            or self._last_status.battery != status.battery
        )
        self._last_status = status
        if not changed and not force:
            return False
        return self._publish(self.topics["status"], status.to_json())

    def publish_heartbeat(self) -> bool:
        if self._last_status is None:
            return False
        refreshed = StatusMessage.create(
            self._last_status.forklift_id,
            self._last_status.status,
            self._last_status.battery,
        )
        return self.publish_status(refreshed, force=True)

    def publish_location(self, location: LocationMessage) -> bool:
        now = self._clock()
        if now - self._last_location_publish < self._location_interval:
            return False
        self._last_location_publish = now
        return self._publish(self.topics["location"], location.to_json())

    def publish_path(self, path: PathMessage) -> bool:
        digest = sha256(path.fingerprint().encode("utf-8")).hexdigest()
        if digest == self._last_path_digest:
            return False
        self._last_path_digest = digest
        return self._publish(self.topics["path"], path.to_json())
