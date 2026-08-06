"""MOVE 명령을 Nav2 `NavigateToPose` 로 실행하는 어댑터 (S15P11A304-192).

종전에는 `UnavailableCommandAdapter` 가 모든 MOVE 를 `REJECTED` 로 돌려보냈다
("team confirmation required"). 그래서 백엔드가 **도착 SUCCESS 를 영영 못 받아**
`fast/station/measure_request` 를 발행하지 않았고, 자동 측정 전체가 시작되지 않았다.
이 어댑터가 그 빈자리를 채운다.

    백엔드 MOVE → [이 어댑터] → Nav2 → 도착 → SUCCESS → 백엔드가 측정 요청 발행

## 왜 rclpy 를 여기서 import 하지 않는가

액션 클라이언트를 **주입받는다**(`goal_sender`). 그래야 rclpy·nav2_msgs 없이 단위
테스트가 돌고, 다른 패키지(`command_handler`)가 지켜온 "ROS 의존 없는 순수 로직"
방침과도 맞는다. ROS 배선은 `mqtt_bridge_node` 가 한다.

## 결과 전이는 계약이 정한 순서를 지킨다

`ResultStateTracker` 가 허용하는 전이만 통과한다(어긴 값은 조용히 버려진다).

    None → ACCEPTED → IN_PROGRESS → SUCCESS | FAILED | CANCELLED

⚠️ **goal 이 거절되면 `REJECTED` 가 아니라 `FAILED` 다.** `REJECTED` 는 명령 자체가
말이 안 될 때(형식·대상 오류) 쓰는 값이고 `validate_command` 가 이미 그 역할을 한다.
Nav2 가 목표를 못 받는 것은 형식 문제가 아니라 **실행 실패**다 — 여기서 REJECTED 를
쓰면 백엔드가 "잘못된 명령을 보냈다"로 읽어 원인을 엉뚱한 데서 찾는다.
"""

from __future__ import annotations

import math
from typing import Any, Callable, Optional, Protocol

EmitResult = Callable[[str, str], None]


class GoalSender(Protocol):
    """Nav2 액션 클라이언트에서 이 어댑터가 쓰는 것만 추린 것."""

    def wait_for_server(self, timeout_sec: float) -> bool:
        ...

    def send_goal(self, x: float, y: float, yaw_rad: float, frame_id: str,
                  on_accepted: Callable[[bool], None],
                  on_done: Callable[[bool, str], None]) -> None:
        """목표를 보내고 **즉시 돌아온다.**

        수락 여부는 `on_accepted(accepted)`, 최종 결과는 `on_done(succeeded, message)`
        로 알린다. 블로킹하면 MQTT 콜백 스레드가 주행 시간(수십 초) 동안 묶여
        하트비트·위치 발행이 멎는다.
        """


def heading_to_yaw_rad(heading_deg: float) -> float:
    """계약의 heading(0~360°, degree) → ROS yaw(rad).

    계약이 `[0, 360)` 을 강제하므로(`validate_command`) 여기서 범위를 다시 보지 않는다.
    다만 Nav2 는 `(-π, π]` 기준이라 **180° 를 넘는 값을 그대로 넣으면 반대로 돈다** —
    한 바퀴를 접어 넣는다.
    """
    return math.atan2(math.sin(math.radians(heading_deg)),
                      math.cos(math.radians(heading_deg)))


class Nav2CommandAdapter:
    """MOVE → Nav2. 그 밖의 명령은 이 어댑터가 받지 않는다.

    `COMMAND_RULES` 에서 ROS2 대상은 MOVE 하나뿐이고 나머지는 EMBEDDED(MCU) 라
    `validate_command` 가 앞에서 걸러낸다. 그래도 방어적으로 확인한다 — 규칙이
    늘어났을 때 조용히 엉뚱한 명령을 주행으로 바꾸면 안 된다.
    """

    def __init__(self, goal_sender: GoalSender,
                 server_wait_s: float = 5.0,
                 logger: Optional[Any] = None) -> None:
        self._sender = goal_sender
        self._server_wait_s = server_wait_s
        self._log = logger

    def execute(self, command: Any, emit_result: EmitResult) -> None:
        if command.command != "MOVE":
            emit_result("FAILED", f"Nav2 adapter cannot execute {command.command}")
            return

        destination = command.destination
        if destination is None:                     # validate_command 가 이미 막지만
            emit_result("FAILED", "MOVE destination is missing")
            return

        # **서버 확인을 먼저 한다.** Nav2 가 안 떠 있는데 목표만 보내면 응답이 영영
        # 안 와서 백엔드 쪽 300초 TTL 이 지나야 실패를 안다. 그 사이 관제에는
        # "이동 중"으로 보인다 — 조용히 멈춘 것처럼 보이는 실패라 먼저 끊는다.
        if not self._sender.wait_for_server(self._server_wait_s):
            emit_result("FAILED",
                        f"Nav2 action server not available after {self._server_wait_s}s "
                        f"(nav2_bringup 이 떠 있는지 확인)")
            return

        emit_result("ACCEPTED", "goal sent to Nav2")

        def on_accepted(accepted: bool) -> None:
            if accepted:
                emit_result("IN_PROGRESS", "navigating")
            else:
                emit_result("FAILED", "Nav2 rejected the goal")

        def on_done(succeeded: bool, message: str) -> None:
            # 계약: **Nav2 목표 도착이 성공했을 때만 SUCCESS.** 중간에 취소되거나
            # 복구 동작만 하다 끝난 것을 SUCCESS 로 보내면 백엔드가 도착하지 않은
            # 차량에 대고 측정을 시작한다.
            emit_result("SUCCESS" if succeeded else "FAILED", message)

        self._sender.send_goal(
            x=float(destination.x),
            y=float(destination.y),
            yaw_rad=heading_to_yaw_rad(float(destination.heading)),
            frame_id=str(destination.frame_id),
            on_accepted=on_accepted,
            on_done=on_done,
        )
