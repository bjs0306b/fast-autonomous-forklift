"""Run one simulator task end to end: go, pick up, go, put down.

`sim_task_receiver` turns an MQTT task into `/mission/task` -- and until now
**nothing subscribed to it.** The conversion was correct and the message went
nowhere. This is the other half: it takes that mission and actually drives it.

    /mission/task  ->  [ NAV 픽업 ]  ->  [ ALIGN 진입·들기 ]
                       [ NAV 적재지 ] ->  [ ALIGN 내려놓기 ]  ->  /mission/status

⚠️ **NAV 와 ALIGN 은 다른 노드가 운전한다.** 파렛에 다가가는 마지막 30 cm 는
   nav2 가 못 한다 -- 플래너에게 파렛은 비켜 가야 할 장애물이라, 목표를 파렛
   위에 두면 경로가 아예 안 나온다. 그래서 그 구간만 `drive_mux` 를 ALIGN 으로
   돌려 비전 정렬 노드에게 운전대를 넘긴다.

⚠️ **정렬 노드가 없어도 미션은 끝까지 간다.** 비전·AI 정렬은 따로 개발 중이라
   데모 직전까지 없을 수 있다. `/align/request` 를 아무도 안 듣고 있으면
   정렬을 건너뛰고 포크 동작만 한다 -- 그래야 나머지 사슬(수신·항법·포크·보고)을
   지금 검증할 수 있다. 건너뛴 사실은 상태에 `alignSkipped` 로 남긴다.

⚠️ **실패해도 운전대는 NAV 로 돌려준다.** ALIGN 인 채 죽으면 그 다음 목표가
   아무 데도 안 간다 -- 중재기는 모드가 낡았다고 NAV 로 되돌리지 않기 때문이다
   (그건 포크가 파렛 안에 있는 채 nav2 가 끼어드는 것을 막으려는 설계다).

⚠️ **좌표는 이미 실물 미터다.** 시뮬 축척(x10) 변환은 MQTT 경계에서 한 번만
   한다. 여기서 또 나누면 두 번 나뉜다.
"""

import json
import math
import time
from typing import Optional

from action_msgs.msg import GoalStatus
from nav2_msgs.action import NavigateToPose
import rclpy
from rclpy.action import ActionClient
from rclpy.node import Node
from rclpy.qos import QoSDurabilityPolicy, QoSProfile
from std_msgs.msg import String

NAV = "NAV"
ALIGN = "ALIGN"


class MissionRunner(Node):
    def __init__(self) -> None:
        super().__init__("mission_runner")

        self.declare_parameter("mission_topic", "/mission/task")
        self.declare_parameter("status_topic", "/mission/status")
        self.declare_parameter("mode_topic", "/drive/mode")
        self.declare_parameter("align_request_topic", "/align/request")
        self.declare_parameter("align_result_topic", "/align/result")
        self.declare_parameter("fork_command_topic", "/fork/command")
        self.declare_parameter("fork_status_topic", "/fork/status")
        self.declare_parameter("map_frame", "map")
        self.declare_parameter("navigation_timeout_sec", 120.0)
        self.declare_parameter("align_timeout_sec", 45.0)
        self.declare_parameter("fork_timeout_sec", 30.0)
        # 정렬 노드가 붙었는지 판단하는 시간. 짧으면 늦게 뜬 노드를 놓치고,
        # 길면 노드가 아예 없을 때 매 구간이 그만큼 늦어진다.
        self.declare_parameter("align_probe_sec", 2.0)

        self._map_frame = str(self.get_parameter("map_frame").value)
        self._nav_timeout = float(
            self.get_parameter("navigation_timeout_sec").value)
        self._align_timeout = float(
            self.get_parameter("align_timeout_sec").value)
        self._fork_timeout = float(self.get_parameter("fork_timeout_sec").value)
        self._align_probe = float(self.get_parameter("align_probe_sec").value)

        self._pending: Optional[dict] = None
        self._running = False
        self._align_result: Optional[dict] = None
        self._fork_running_seen = False
        self._fork_done = False
        self._fork_error: Optional[str] = None

        latched = QoSProfile(depth=1)
        latched.durability = QoSDurabilityPolicy.TRANSIENT_LOCAL

        self.create_subscription(
            String, str(self.get_parameter("mission_topic").value),
            self._on_task, 10)
        self.create_subscription(
            String, str(self.get_parameter("align_result_topic").value),
            self._on_align_result, 10)
        self.create_subscription(
            String, str(self.get_parameter("fork_status_topic").value),
            self._on_fork_status, 10)

        self._status = self.create_publisher(
            String, str(self.get_parameter("status_topic").value), latched)
        self._mode = self.create_publisher(
            String, str(self.get_parameter("mode_topic").value), latched)
        self._align = self.create_publisher(
            String, str(self.get_parameter("align_request_topic").value), 10)
        self._fork = self.create_publisher(
            String, str(self.get_parameter("fork_command_topic").value), 10)

        self._navigate = ActionClient(self, NavigateToPose, "navigate_to_pose")

        self.get_logger().info(
            f"Mission runner ready: {self.get_parameter('mission_topic').value}"
            f" -> NAV/ALIGN"
        )

    # ---- 들어오는 것 -------------------------------------------------

    def _on_task(self, message: String) -> None:
        """작업을 받아 둔다. 실행은 여기서 하지 않는다.

        콜백 안에서 실행하면 그 안에서 다시 spin 을 돌려야 하는데, 같은 노드를
        재진입시키는 일이라 액션 결과가 영영 안 온다. 받아만 두고 주 루프가
        꺼내 간다.
        """
        try:
            task = json.loads(message.data)
        except (TypeError, ValueError) as error:
            self.get_logger().error(f"작업을 못 읽었다: {error}")
            return
        if self._running:
            self.get_logger().warning(
                f"작업 {task.get('taskId')} 을 거절한다 -- 아직 앞 작업이 돈다")
            self._report(task.get("taskId"), "REJECTED",
                         detail="앞 작업이 끝나지 않았다")
            return
        self._pending = task
        self.get_logger().info(f"작업 접수: {task.get('taskId')}")

    def _on_align_result(self, message: String) -> None:
        try:
            self._align_result = json.loads(message.data)
        except (TypeError, ValueError):
            self._align_result = {"state": "ERROR", "detail": message.data}

    def _on_fork_status(self, message: String) -> None:
        try:
            status = json.loads(message.data)
        except (TypeError, ValueError):
            return
        state = str(status.get("state", "")).upper()
        if state == "RUNNING":
            self._fork_running_seen = True
        elif state == "DONE" and self._fork_running_seen:
            self._fork_done = True
        elif state == "ERROR":
            self._fork_error = message.data

    # ---- 나가는 것 ---------------------------------------------------

    def _report(self, task_id, stage: str, **extra) -> None:
        payload = {"taskId": task_id, "stage": stage,
                   "ts": int(time.time() * 1000)}
        payload.update(extra)
        self._status.publish(String(data=json.dumps(payload)))
        self.get_logger().info(f"[{task_id}] {stage} {extra if extra else ''}")

    def _set_mode(self, mode: str) -> None:
        self._mode.publish(String(data=mode))
        self._spin_for(0.3)

    # ---- 단계 --------------------------------------------------------

    def _spin_for(self, seconds: float) -> None:
        deadline = time.monotonic() + seconds
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.05)

    def _spin_until(self, future, timeout_sec: float) -> bool:
        deadline = time.monotonic() + timeout_sec
        while rclpy.ok() and not future.done():
            if time.monotonic() >= deadline:
                return False
            rclpy.spin_once(self, timeout_sec=0.1)
        return future.done()

    def navigate_to(self, label: str, point: dict) -> bool:
        self._set_mode(NAV)
        if not self._navigate.wait_for_server(timeout_sec=10.0):
            self.get_logger().error("navigate_to_pose 서버가 없다")
            return False

        yaw = float(point.get("yaw", 0.0))
        goal = NavigateToPose.Goal()
        goal.pose.header.frame_id = self._map_frame
        goal.pose.header.stamp = self.get_clock().now().to_msg()
        goal.pose.pose.position.x = float(point["x"])
        goal.pose.pose.position.y = float(point["y"])
        goal.pose.pose.orientation.z = math.sin(yaw / 2.0)
        goal.pose.pose.orientation.w = math.cos(yaw / 2.0)

        self.get_logger().info(
            f"{label} 로 간다: ({point['x']:.2f}, {point['y']:.2f}) "
            f"yaw {yaw:.3f} rad")
        sent = self._navigate.send_goal_async(goal)
        if not self._spin_until(sent, 10.0):
            self.get_logger().error(f"{label} 목표 전송이 늦다")
            return False
        handle = sent.result()
        if handle is None or not handle.accepted:
            self.get_logger().error(f"{label} 목표가 거절됐다")
            return False

        result = handle.get_result_async()
        if not self._spin_until(result, self._nav_timeout):
            self.get_logger().error(f"{label} 항법 시간 초과")
            self._spin_until(handle.cancel_goal_async(), 5.0)
            return False
        outcome = result.result()
        if outcome is None or outcome.status != GoalStatus.STATUS_SUCCEEDED:
            self.get_logger().error(
                f"{label} 항법 실패 (status="
                f"{None if outcome is None else outcome.status})")
            return False
        return True

    def align(self, what: str, point: dict) -> Optional[bool]:
        """정렬 노드에게 맡긴다. 아무도 없으면 None 을 돌려준다."""
        if self._align.get_subscription_count() == 0:
            self._spin_for(self._align_probe)
        if self._align.get_subscription_count() == 0:
            self.get_logger().warning(
                "정렬 노드가 없다 -- 정렬을 건너뛰고 포크만 움직인다")
            return None

        self._align_result = None
        self._set_mode(ALIGN)
        self._align.publish(String(data=json.dumps({
            "action": what,
            "target": {"x": point["x"], "y": point["y"],
                       "yaw": point.get("yaw", 0.0)},
        })))

        deadline = time.monotonic() + self._align_timeout
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.1)
            if self._align_result is not None:
                state = str(self._align_result.get("state", "")).upper()
                if state == "DONE":
                    return True
                if state == "ERROR":
                    self.get_logger().error(
                        f"정렬 실패: {self._align_result.get('detail')}")
                    return False
        self.get_logger().error("정렬 시간 초과")
        return False

    def fork(self, action: str) -> bool:
        self._fork_running_seen = False
        self._fork_done = False
        self._fork_error = None
        self._fork.publish(String(data=action))

        deadline = time.monotonic() + self._fork_timeout
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(self, timeout_sec=0.1)
            if self._fork_error is not None:
                self.get_logger().error(f"포크 {action} 실패: {self._fork_error}")
                return False
            if self._fork_done:
                return True
        self.get_logger().error(f"포크 {action} 시간 초과")
        self._fork.publish(String(data="STOP"))
        return False

    # ---- 미션 --------------------------------------------------------

    def goto(self, task: dict) -> bool:
        """목적지 하나로 가고 끝. 포크도 정렬도 없다.

        관제가 순환로를 따라 **다음 지점을 하나씩** 보내는 경우다
        (orin-pose-spec §6.2). 픽업·적재 한 쌍이 아니라 좌표 하나만 온다.
        """
        task_id = task.get("taskId")
        goal = task.get("goal")
        if not isinstance(goal, dict) or "x" not in goal:
            self._report(task_id, "FAILED", detail="목적지 좌표가 없다")
            return False
        self._report(task_id, "NAV")
        if not self.navigate_to("목적지", goal):
            self._report(task_id, "FAILED", detail="항법 실패")
            return False
        self._report(task_id, "ARRIVED", x=round(goal["x"], 3),
                     y=round(goal["y"], 3))
        return True

    def run(self, task: dict) -> bool:
        task_id = task.get("taskId")
        self._running = True
        skipped = []
        try:
            if str(task.get("action", "")).upper() == "GOTO":
                return self.goto(task)
            for label, point, lift in (
                ("픽업", task.get("pickup"), "UP"),
                ("적재", task.get("dropoff"), "DOWN"),
            ):
                if not isinstance(point, dict) or "x" not in point:
                    self._report(task_id, "FAILED",
                                 detail=f"{label} 좌표가 없다")
                    return False

                self._report(task_id, f"NAV_{label}")
                if not self.navigate_to(label, point):
                    self._report(task_id, "FAILED", detail=f"{label} 항법 실패")
                    return False

                self._report(task_id, f"ALIGN_{label}")
                aligned = self.align(
                    "PICKUP" if lift == "UP" else "DROPOFF", point)
                if aligned is None:
                    skipped.append(label)
                elif not aligned:
                    self._report(task_id, "FAILED", detail=f"{label} 정렬 실패")
                    return False

                self._report(task_id, f"FORK_{label}")
                if not self.fork(lift):
                    self._report(task_id, "FAILED", detail=f"{label} 포크 실패")
                    return False

            self._report(task_id, "COMPLETED",
                         alignSkipped=skipped or None)
            return True
        finally:
            # ⚠️ 어떤 경로로 끝나든 운전대는 NAV 로. ALIGN 인 채 남으면 다음
            #    작업이 통째로 안 움직인다.
            self._set_mode(NAV)
            self._running = False

    def pump(self) -> None:
        if self._pending is None:
            return
        task, self._pending = self._pending, None
        self.run(task)


def main(args=None) -> None:
    rclpy.init(args=args)
    node = MissionRunner()
    try:
        while rclpy.ok():
            rclpy.spin_once(node, timeout_sec=0.1)
            node.pump()
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
