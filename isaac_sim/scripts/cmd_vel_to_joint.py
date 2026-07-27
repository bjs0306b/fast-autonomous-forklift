#!/usr/bin/env python3
"""
cmd_vel -> joint_command 변환 노드 (3륜 지게차용)

Nav2가 내는 표준 /cmd_vel(Twist)을 받아, Isaac 지게차의 조인트 명령으로 바꾼다.
Isaac 그래프는 건드리지 않는다 — 이미 joint_command를 구독하고 있으므로
이 노드만 띄우면 Nav2가 시뮬 지게차를 굴릴 수 있다.

구조: 뒷바퀴 1개가 구동과 조향을 겸하는 3륜(tricycle) 형태.
      회전 중심은 앞축 부근에 생기고, 조향 응답이 앞바퀴 조향과 반대다.
      (요구사항명세서 §EPIC-3 "조향축 정정" 참고)

실행 (기본값이 보정 완료된 값이라 인자 없이 그대로 실행하면 된다):
    python3 cmd_vel_to_joint.py

애셋을 교체했다면 파라미터를 다시 잡아야 한다:
    python3 cmd_vel_to_joint.py --ros-args \
        -p wheelbase:=1.62 -p wheel_radius:=0.17 -p steer_sign:=-1.0
"""

import math

import rclpy
from rclpy.node import Node
from geometry_msgs.msg import Twist
from sensor_msgs.msg import JointState


class CmdVelToJoint(Node):

    def __init__(self):
        super().__init__("cmd_vel_to_joint")

        # --- 파라미터 ---
        # wheelbase / wheel_radius 는 odom 역산으로 보정한 값이다(2026-07-22).
        #   명령 0.200m/s · 0.067rad/s  ->  실측 0.200m/s · 0.0663rad/s (반경 3.02m / 목표 3.0m)
        # 애셋을 교체하면 다시 보정해야 한다. 보정 절차는 파일 하단 주석 참고.
        self.declare_parameter("wheelbase", 1.62)        # 앞축 ~ 뒷바퀴 거리 (m)
        self.declare_parameter("wheel_radius", 0.17)     # 구동륜 반지름 (m)
        self.declare_parameter("max_steer", 0.6)         # 최대 조향각 (rad)
        # -> 최소 회전반경 = wheelbase / tan(max_steer) = 2.37m
        #    Nav2 설정에 그대로 들어가는 값이다(D 전달).
        self.declare_parameter("max_wheel_speed", 30.0)  # 구동 조인트 최대 각속도 (rad/s)
        # 조향 부호. 지게차가 명령과 반대로 돌면 이 값을 뒤집는다.
        # 뒷바퀴 조향은 앞바퀴 조향과 응답이 반대라 보통 -1.0이 맞다.
        self.declare_parameter("steer_sign", -1.0)

        self.declare_parameter("drive_joint", "back_wheel_drive")
        self.declare_parameter("steer_joint", "back_wheel_swivel")

        self.declare_parameter("cmd_vel_topic", "/SIM_F01/cmd_vel")
        self.declare_parameter("joint_command_topic", "/SIM_F01/joint_command")

        p = self.get_parameter
        self.L = p("wheelbase").value
        self.r = p("wheel_radius").value
        self.max_steer = p("max_steer").value
        self.max_wheel_speed = p("max_wheel_speed").value
        self.steer_sign = p("steer_sign").value
        self.drive_joint = p("drive_joint").value
        self.steer_joint = p("steer_joint").value

        self.pub = self.create_publisher(
            JointState, p("joint_command_topic").value, 10)
        self.create_subscription(
            Twist, p("cmd_vel_topic").value, self._on_cmd_vel, 10)

        # Nav2가 멈추거나 죽었을 때 지게차가 계속 굴러가지 않게 하는 안전장치.
        # cmd_vel이 끊기면 정지시킨다.
        self._last_cmd_time = self.get_clock().now()
        self.create_timer(0.1, self._watchdog)

        self.get_logger().info(
            f"cmd_vel -> joint_command  (L={self.L}m, r={self.r}m, "
            f"steer_sign={self.steer_sign})")

    # ------------------------------------------------------------------
    def _on_cmd_vel(self, msg: Twist):
        self._last_cmd_time = self.get_clock().now()
        v = msg.linear.x        # 전진 속도 (m/s)
        w = msg.angular.z       # 회전 속도 (rad/s)

        # Ackermann은 제자리 회전이 안 된다. 전진 성분이 거의 없으면
        # 조향만 유지하고 멈춘다. (억지로 돌리려 하면 값이 발산한다)
        if abs(v) < 1e-3:
            self._publish(0.0, 0.0)
            return

        # 뒷바퀴 조향각.
        # 뒷바퀴 조향이라 앞바퀴 조향식과 부호가 반대다 -> steer_sign으로 보정.
        steer = self.steer_sign * math.atan2(w * self.L, abs(v))
        steer = max(-self.max_steer, min(self.max_steer, steer))

        # 구동륜은 차체보다 비스듬히 더 긴 거리를 지나므로 cos으로 나눈다.
        wheel_speed = v / max(math.cos(steer), 1e-3) / self.r
        wheel_speed = max(-self.max_wheel_speed,
                          min(self.max_wheel_speed, wheel_speed))

        self._publish(wheel_speed, steer)

    def _watchdog(self):
        dt = (self.get_clock().now() - self._last_cmd_time).nanoseconds * 1e-9
        if dt > 0.5:
            self._publish(0.0, 0.0)

    def _publish(self, wheel_speed, steer):
        # 구동륜은 속도 제어, 조향륜은 위치 제어라 메시지를 따로 보낸다.
        # 한 메시지에 섞으면 Isaac이 position을 우선해 구동륜이 멈춘다.
        drive = JointState()
        drive.header.stamp = self.get_clock().now().to_msg()
        drive.name = [self.drive_joint]
        drive.velocity = [float(wheel_speed)]
        self.pub.publish(drive)

        steer_msg = JointState()
        steer_msg.header.stamp = self.get_clock().now().to_msg()
        steer_msg.name = [self.steer_joint]
        steer_msg.position = [float(steer)]
        self.pub.publish(steer_msg)


def main():
    rclpy.init()
    node = CmdVelToJoint()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node._publish(0.0, 0.0)   # 종료 시 정지
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()


# ---------------------------------------------------------------------------
# 파라미터 보정 절차 (애셋을 바꾸면 다시 한다)
#
# odom 이 "실제로 어떻게 움직였는지" 알려주므로 그걸로 역산한다.
# 자로 재는 것보다 정확하다 — 미끄러짐 같은 물리 거동이 반영되기 때문이다.
#
#   1) 명령을 쏘고           ros2 topic pub /SIM_F01/cmd_vel geometry_msgs/msg/Twist \
#                              "{linear: {x: 0.2}, angular: {z: 0.067}}" -r 10
#   2) 실제 값을 읽는다      ros2 topic echo /SIM_F01/raw/odom --once
#
#   3) 먼저 wheel_radius (속도). 속도가 맞아야 회전을 제대로 잴 수 있다.
#        실제속도 = hypot(twist.linear.x, twist.linear.y)
#        r_새값 = r_현재 * (실제속도 / 명령속도)
#
#   4) 그다음 wheelbase (회전).
#        실제반경 = 실제속도 / twist.angular.z
#        조향각   = atan(wheelbase_현재 * 명령각속도 / 명령속도)
#        L_새값   = 실제반경 * tan(조향각)
#
#   5) 다시 쏴서 odom 이 명령값에 수렴하는지 확인. 두세 번 반복하면 맞는다.
#
# 주의: 명령이 최소 회전반경보다 작은 원을 요구하면 조향이 max_steer 에서 잘려
#       아무리 보정해도 안 맞는다. 반경 = 명령속도 / 명령각속도 로 먼저 확인할 것.
# ---------------------------------------------------------------------------
