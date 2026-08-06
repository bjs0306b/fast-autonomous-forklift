"""`Nav2CommandAdapter` 가 쓰는 실제 ROS2 액션 클라이언트 (S15P11A304-192).

여기만 rclpy·nav2_msgs 에 의존한다. 어댑터 쪽은 순수 파이썬이라 ROS 없이 테스트된다.

⚠️ **이 모듈은 import 자체가 실패할 수 있다** — 개발 노트북에는 nav2_msgs 가 없다.
`mqtt_bridge_node` 가 try/except 로 감싸고, 실패하면 종전처럼
`UnavailableCommandAdapter` 로 떨어진다(그리고 **그 사실을 로그에 남긴다** — 조용히
거절 모드로 도는 게 지금까지의 문제였다).
"""

from __future__ import annotations

import math
from typing import Callable

from action_msgs.msg import GoalStatus
from geometry_msgs.msg import PoseStamped
from nav2_msgs.action import NavigateToPose
from rclpy.action import ActionClient


class Nav2GoalSender:
    """`NavigateToPose` 한 개를 비동기로 보내고 콜백으로 결과를 알린다."""

    def __init__(self, node, action_name: str = "navigate_to_pose") -> None:
        self._node = node
        self._client = ActionClient(node, NavigateToPose, action_name)
        # 진행 중인 goal 핸들을 들고 있어야 취소할 수 있다.
        self._goal_handle = None

    def wait_for_server(self, timeout_sec: float) -> bool:
        return self._client.wait_for_server(timeout_sec=timeout_sec)

    def send_goal(self, x: float, y: float, yaw_rad: float, frame_id: str,
                  on_accepted: Callable[[bool], None],
                  on_done: Callable[[bool, str], None]) -> None:
        goal = NavigateToPose.Goal()
        goal.pose = self._pose(x, y, yaw_rad, frame_id)

        def _result_cb(future) -> None:
            result = future.result()
            status = getattr(result, "status", None)
            succeeded = status == GoalStatus.STATUS_SUCCEEDED
            on_done(succeeded, self._describe(status))

        def _goal_cb(future) -> None:
            handle = future.result()
            if handle is None or not handle.accepted:
                on_accepted(False)
                return
            self._goal_handle = handle
            on_accepted(True)
            handle.get_result_async().add_done_callback(_result_cb)

        self._client.send_goal_async(goal).add_done_callback(_goal_cb)

    def cancel(self) -> None:
        if self._goal_handle is not None:
            self._goal_handle.cancel_goal_async()

    def _pose(self, x: float, y: float, yaw_rad: float, frame_id: str) -> PoseStamped:
        pose = PoseStamped()
        pose.header.frame_id = frame_id
        pose.header.stamp = self._node.get_clock().now().to_msg()
        pose.pose.position.x = x
        pose.pose.position.y = y
        # 평면 주행이라 yaw 만 있으면 된다 — z 회전 쿼터니언.
        pose.pose.orientation.z = math.sin(yaw_rad / 2.0)
        pose.pose.orientation.w = math.cos(yaw_rad / 2.0)
        return pose

    @staticmethod
    def _describe(status) -> str:
        """상태 코드를 사람이 읽을 문장으로. **실패 원인이 관제까지 간다.**"""
        return {
            GoalStatus.STATUS_SUCCEEDED: "arrived at measuring position",
            GoalStatus.STATUS_ABORTED: "Nav2 aborted (경로 없음·장애물·복구 실패)",
            GoalStatus.STATUS_CANCELED: "Nav2 goal cancelled",
        }.get(status, f"Nav2 finished with status {status}")
