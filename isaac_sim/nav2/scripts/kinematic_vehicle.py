"""
Kinematic simulated forklift (runs inside Isaac Sim).

Instead of the physics engine, this integrates a bicycle model and writes the
prim transform directly. The goal is to reproduce the behaviour of the real
miniature hardware regardless of how the Isaac asset is rigged.

Responsibilities
    sub   /{ns}/cmd_vel        (geometry_msgs/Twist)      <- Nav2
    sub   /{ns}/fork_cmd       (std_msgs/Float32)         <- fork height (m)
    pub   /{ns}/odom           (nav_msgs/Odometry)
    pub   /tf                  odom -> base_link (every frame)
    pub   /tf_static           base_link -> sensors (once)

What stays in the Isaac Action Graph
    ROS2Context / OnPlaybackTick / ROS2PublishClock (one per scene)
    ROS2RtxLidarHelper (per vehicle) - raycasting needs the render engine.

Warning
    Do not split TF between the graph and this script. Two publishers with
    different timing make the tree jitter. Remove every TF node from the
    Action Graph for vehicles driven by this script.

Usage
    Paste into the Isaac Sim Script Editor and run. Stop with fleet.stop().
"""

import json
import math
import threading

import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, QoSDurabilityPolicy
from geometry_msgs.msg import Twist, TransformStamped
from nav_msgs.msg import Odometry
from std_msgs.msg import Float32, String
from tf2_msgs.msg import TFMessage

# The import path depends on the Isaac Sim version.
#   <= 4.0 : from omni.isaac.core.prims import XFormPrim
#   >= 4.5 : from isaacsim.core.prims import SingleXFormPrim as XFormPrim
# This machine runs Isaac Sim 5.1, so use the new path.
from isaacsim.core.prims import SingleXFormPrim as XFormPrim
import omni.kit.app


# ---------------------------------------------------------------------------
# Vehicle parameters
#
# These describe the miniature hardware. The twin scene is built at miniature
# 1:1 scale, so measurements from C (embedded) go in as-is - no 1/10 conversion.
# The values below are assumptions made before the hardware exists; replace them
# once the real forklift is measured (see the calibration note at the bottom).
# ---------------------------------------------------------------------------
# 스텝 튜플 형식이 바뀌면 올린다. cargo_demo 가 이 값을 보고
# 시간 제한이 붙은 스텝을 써도 되는지 판단한다.
VEHICLE_VERSION = 8

DEFAULT_PARAMS = {
    # Full-size warehouse scale, but the STEERING CHARACTER is matched to the
    # real Orin car (rear-wheel steer, 30 deg max, MG996R servo). Turning is
    # scale-free: R_min/L = 1/tan(max_steer), so 30 deg gives R_min = 1.73*L
    # whatever the wheelbase is. Keeping L=1.6 (scene scale) + 30 deg makes the
    # sim forklift turn with the same tightness-relative-to-size as the real car.
    # Measured from the real miniature (14 cm wheelbase, 60 deg steer) x10 for
    # the full-size twin scene. min turning radius = 1.4/tan(60) = 0.81 m.
    "wheelbase": 1.4,       # front axle to rear axle (m) - 14 cm x10
    "max_steer": 1.047,     # max steering angle (rad, 60 deg) - real Orin car
    "max_speed": 2.0,       # max forward speed (m/s, ~7 km/h) - laden travel
    "max_accel": 1.0,       # max acceleration (m/s^2) - prevents jerky starts
    "max_steer_rate": 5.0,  # max steering rate (rad/s) - MG996R ~0.15 s / 60 deg
    # Fork (FR-303). The stepper is slow, so the rate limit matters visually.
    # NOTE: these were miniature (0.16 m) values in the original file, but the
    # twin scene is now full-size (10x), so the fork numbers are scaled up to
    # match. Drive params (wheelbase/steer) were already full-size and unchanged.
    "lift_min": 0.0,        # lowest fork position (m, local to the lift prim)
    "lift_max": 1.5,        # highest fork position (m) - 0.15 * 10
    "lift_rate": 0.5,       # lift speed (m/s) - 0.05 * 10
    "lift_axis": 2,
    # 미러(실물 추종) 차량의 보간. 실물은 10 Hz 로 좌표를 보내는데 화면은
    # 60 fps 라, 그대로 대입하면 뚝뚝 끊겨 보인다.
    "mirror_smooth": 0.12,  # 목표에 따라붙는 시간(초). 크면 부드럽고 더 뒤처진다
    "mirror_snap": 2.0,     # 이보다 크게 튀면 보간 없이 즉시 이동 (m, 시뮬)         # which local axis moves the fork: 0=X, 1=Y, 2=Z
    # Where a pallet rests on the forks, relative to base_link.
    "fork_offset_x": 1.2,   # forward of base_link (m) - measured from lift prim
    "fork_offset_z": 0.1,   # above the ground at lift_min (m)
    # Pickup tolerance, scaled to full size.
    "pick_xy_tol": 0.12,    # 0.012 * 10
    "pick_z_tol": 0.2,      # 0.02 * 10
}
# min turning radius = wheelbase / tan(max_steer) = 1.4 / tan(1.047) = 0.81 m
# This value goes straight into the Nav2 config (minimum_turning_radius) and also
# determines the minimum aisle width in the warehouse layout. Keep the two in
# sync: if Nav2 plans wider turns than the vehicle needs, it fails to find paths
# in tight aisles and spins in recovery.

# Sensor mounting positions relative to base_link (m), measured in the scene.
# Full-size (10x) values. laser height matches the RTX lidar in the Action Graph.
SENSOR_TF = {
    "laser":     (0.0,  0.0, 1.2),
    "tof_left":  (1.4,  0.5, 0.3),
    "tof_right": (1.4, -0.5, 0.3),
}


def yaw_to_quat(yaw):
    """yaw (rad) -> (x, y, z, w), ROS quaternion order."""
    half = yaw * 0.5
    return (0.0, 0.0, math.sin(half), math.cos(half))


def yaw_to_quat_usd(yaw):
    """yaw (rad) -> (w, x, y, z), USD quaternion order."""
    half = yaw * 0.5
    return (math.cos(half), 0.0, 0.0, math.sin(half))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class KinematicVehicle:
    """One vehicle: consumes cmd_vel, integrates a bicycle model, moves the prim."""

    def __init__(self, node, vehicle_id, prim_path, params=None, spawn=(0.0, 0.0, 0.0),
                 lift_prim=None):
        self.node = node
        self.vid = vehicle_id
        self.ns = vehicle_id.lower()          # SIM_F02 -> sim_f02
        # Keep the path so we can re-grab the prim if its handle expires.
        # Stop/Play, reopening the scene, or adding/removing prims (markers,
        # cargo) rebuilds the stage and invalidates cached prim objects. Without
        # this the per-frame set_world_pose throws "Accessed invalid expired
        # Xform prim" every frame until the script is restarted.
        self.prim_path = prim_path
        self.prim = XFormPrim(prim_path)
        self.p = dict(DEFAULT_PARAMS, **(params or {}))

        self.frame_base = f"{vehicle_id}_base_link"
        self.frame_odom = f"{vehicle_id}_odom"

        # State
        self.x, self.y, self.yaw = spawn
        self.v = 0.0        # current speed after acceleration limiting
        self.steer = 0.0    # current steering angle after rate limiting

        # Command - written by the ROS callback thread, read by the main thread.
        self._cmd_v = 0.0
        self._cmd_w = 0.0
        self._cmd_lock = threading.Lock()
        self._last_cmd_time = node.get_clock().now()

        self.odom_pub = node.create_publisher(Odometry, f"/{self.ns}/odom", 10)
        # 상태 요약(JSON 문자열). MQTT 브릿지가 이걸 그대로 실어 보낸다.
        # 포크 높이·적재 여부는 이 노드만 알고 있으므로 여기서 내보내야 한다.
        self.state_pub = node.create_publisher(String, f"/{self.ns}/state", 10)
        self._state_t = 0.0
        # Subscribe to BOTH the namespaced topic and the plain /cmd_vel. Nav2 is
        # launched without a namespace, so its final (collision-monitor) output
        # lands on /cmd_vel; manual tests use /{ns}/cmd_vel. Listening to both
        # means the same vehicle node works with Nav2 and with hand-sent Twists.
        node.create_subscription(
            Twist, f"/{self.ns}/cmd_vel", self._on_cmd_vel, 10)
        node.create_subscription(
            Twist, "/cmd_vel", self._on_cmd_vel, 10)

        # Fork. The lift prim is a child of the vehicle, so it must be driven in
        # LOCAL coordinates - set_world_pose would detach it and leave the forks
        # behind when the truck drives off.
        self.lift_path = lift_prim
        self.lift = XFormPrim(lift_prim) if lift_prim else None
        self.lift_height = self.p["lift_min"]
        self._lift_target = self.p["lift_min"]
        self._lift_base = None
        if self.lift is not None:
            self._lift_base = list(self.lift.get_local_pose()[0])
            node.create_subscription(
                Float32, f"/{self.ns}/fork_cmd", self._on_fork_cmd, 10)

        # Cargo. A carried load follows the fork by having its world pose
        # rewritten every frame - simpler and more reversible than reparenting
        # inside the USD stage mid-simulation.
        self.cargo = None        # XFormPrim currently carried
        self.cargo_id = None     # id reported over MQTT as cargoId

        # 미러 모드 — 실물 차량의 좌표를 그대로 따라가는 트윈용.
        # True 면 cmd_vel 로 적분하지 않고, 받은 좌표를 그대로 반영한다.
        # 좌표는 ROS 콜백(다른 스레드)에서 들어오므로 값만 저장해 두고,
        # 실제 프림 이동은 메인 스레드의 update() 에서 한다.
        self.mirror = False
        # 싣고 있는 화물의 크기 (시뮬 단위). 실물 기준이면 ÷10.
        self.cargo_size = None      # {"w":, "d":, "h":} 또는 None
        # 지금 수행 중인 작업. cargo_demo 가 채운다.
        # 시뮬은 바이에서 화물이 즉시 생성돼 포크가 움직이지 않으므로,
        # 이게 없으면 관제 화면에서 "받는 중" 을 표시할 방법이 없다.
        self.work_state = ""        # "" | "LOADING" | "UNLOADING"
        self._mirror_pose = None
        self._mirror_lock = threading.Lock()

        # Mission queue. Each entry is a step the vehicle works through over
        # many frames (drive there, lower fork, pick, raise, drive to rack,
        # place, reverse out). Empty queue = idle / cmd_vel controlled.
        self._steps = []
        self._step_t = 0.0       # time spent in the current step
        self._dock_warned = False

        self._apply_prim()

    # -- ROS callbacks (separate thread) ------------------------------------
    def _on_cmd_vel(self, msg):
        with self._cmd_lock:
            self._cmd_v = msg.linear.x
            self._cmd_w = msg.angular.z
        self._last_cmd_time = self.node.get_clock().now()

    def _on_fork_cmd(self, msg):
        # 포크가 예고 없이 내려가는 일이 있어 출처를 남긴다.
        # /{ns}/fork_cmd 로 0 을 쏘는 쪽이 있으면 여기 찍힌다.
        want = clamp(msg.data, self.p["lift_min"], self.p["lift_max"])
        if abs(want - self._lift_target) > 1e-3:
            self.node.get_logger().info(
                f"{self.vid} fork_cmd 수신 {self._lift_target:.2f} -> {want:.2f}"
                f"  (발행자를 확인하려면: ros2 topic info /{self.ns}/fork_cmd -v)")
        self._lift_target = want

    # -- Per frame (main thread) --------------------------------------------
    def update(self, dt):
        if self._steps:
            # A mission is running: the step machine drives the vehicle.
            cmd_v, cmd_w = self._run_steps(dt)
        else:
            with self._cmd_lock:
                cmd_v, cmd_w = self._cmd_v, self._cmd_w
            # Stop if Nav2 dies or the link drops. On real hardware, a crash.
            age = (self.node.get_clock().now() - self._last_cmd_time).nanoseconds * 1e-9
            if age > 0.5:
                cmd_v, cmd_w = 0.0, 0.0

        # Skip the whole visual update if the prim handle is dead (stage was
        # rebuilt). _ensure_prim re-grabs it; if it truly is gone, we skip this
        # frame rather than spamming "expired Xform prim" errors. Odom/TF still
        # publish below so Nav2 keeps a consistent (last-known) pose.
        if self.mirror:
            # 실물을 따라가는 차량은 스스로 움직이지 않는다.
            #
            # 받은 좌표를 그 프레임에 그대로 대입하면, 10 Hz 로 오는 값을
            # 60 fps 화면에 찍는 셈이라 6프레임 멈췄다 한 번 점프한다.
            # 그래서 목표 자세로 부드럽게 따라붙는다(보간).
            #   - MIRROR_SMOOTH 가 클수록 부드럽지만 더 뒤처진다
            #   - 한 번에 크게 튀면(AMCL 재정합 등) 보간하지 않고 즉시 옮긴다.
            #     창고를 가로질러 미끄러지는 것보다 순간이동이 덜 어색하다.
            with self._mirror_lock:
                pose = self._mirror_pose
            if pose is not None:
                tx, ty, tyaw = pose
                prev_x, prev_y = self.x, self.y
                jump = math.hypot(tx - self.x, ty - self.y)
                if jump > self.p["mirror_snap"]:
                    self.x, self.y, self.yaw = tx, ty, tyaw
                else:
                    a = clamp(dt / max(self.p["mirror_smooth"], 1e-3), 0.0, 1.0)
                    self.x += (tx - self.x) * a
                    self.y += (ty - self.y) * a
                    e = math.atan2(math.sin(tyaw - self.yaw),
                                   math.cos(tyaw - self.yaw))
                    self.yaw = math.atan2(math.sin(self.yaw + e * a),
                                          math.cos(self.yaw + e * a))
                # 속도는 이동량에서 역산 (telemetry 표시용)
                self.v = math.hypot(self.x - prev_x, self.y - prev_y) / max(dt, 1e-3)
        else:
            self._integrate(cmd_v, cmd_w, dt)
        if self._ensure_prim():
            self._apply_prim()
            self._update_lift(dt)
            self._update_cargo()
        self._publish_odom()
        self._publish_state(dt)

    # -- Mission sequence ----------------------------------------------------
    def run_mission(self, cargo_prim, cargo_id, pickup, rack_xyz,
                    approach=None):
        """Queue a full pick-and-place: drive to pickup, insert forks, lift,
        drive to the rack, lower onto the slot, reverse out.

        pickup   = (x, y)         where the pallet is (drive the forks here)
        rack_xyz = (x, y, z)      rack slot pose to place at
        approach = (x, y) or None optional pre-pickup waypoint to line up
        """
        px, py = pickup
        rx, ry, rz = rack_xyz
        travel = self.p["lift_max"] * 0.7   # carry height while driving
        low = self.p["lift_min"]

        steps = []
        if approach:
            steps.append(("drive", approach[0], approach[1]))
        steps += [
            ("fork", low),                 # forks down to pallet level
            ("drive", px, py),             # insert forks under the pallet
            ("pick", cargo_prim, cargo_id),
            ("fork", travel),              # lift to travel height
            ("drive", rx, ry),             # carry to the rack
            ("fork", rz),                  # raise to slot height
            ("place", rx, ry, rz),         # release onto the slot
            ("fork", low),                 # drop forks clear
            ("reverse", 1.0),              # back out for 1.0 s
        ]
        self._steps = steps
        self._step_t = 0.0
        self.node.get_logger().info(f"{self.vid} mission queued ({len(steps)} steps)")

    def _run_steps(self, dt):
        """Advance the current step; return (cmd_v, cmd_w) for this frame."""
        self._step_t += dt
        step = self._steps[0]
        kind = step[0]
        if not isinstance(kind, str):
            self.node.get_logger().error(f"{self.vid} 잘못된 스텝 {step} — 버림")
            self._next_step()
            return (0.0, 0.0)

        if kind == "drive":
            # ("drive", x, y) 또는 ("drive", x, y, timeout)
            # 시간 제한이 없으면 목표 반경에 못 들어갈 때 영원히 맴돈다.
            # 랙 도킹은 몇십 cm 오차가 나도 place 가 절대 좌표로 놓으므로,
            # 시간이 지나면 포기하고 다음 스텝으로 넘어가는 편이 낫다.
            tx, ty = step[1], step[2]
            timeout = step[3] if len(step) > 3 else 20.0
            done, cmd = self._drive_toward(tx, ty)
            if done:
                self._next_step()
                return cmd
            if self._step_t >= timeout:
                d = math.hypot(tx - self.x, ty - self.y)
                self.node.get_logger().warn(
                    f"{self.vid} drive 시간초과 — 목표까지 {d:.2f} m 남음, 진행")
                self.v = 0.0
                self._next_step()
                return (0.0, 0.0)
            return cmd

        if kind == "dock":
            # ("dock", x, y, yaw, timeout)
            # 목표를 향해 조향하지 않는다. yaw 를 고정한 채 그 방향으로만
            # 곧게 전/후진한다. drive 는 목표점을 향해 조향하기 때문에
            # 랙 코앞에서 조금만 어긋나도 크게 틀어지고, 그 상태로 화물을
            # 놓아 비뚤어진다. 실제 지게차도 각을 잡은 뒤 직진해 들어간다.
            tx, ty, tyaw = step[1], step[2], step[3]
            timeout = step[4] if len(step) > 4 else 15.0

            # 진입 방향 단위벡터와, 그 축 위의 남은 거리(부호 있음)
            cx, cy = math.cos(tyaw), math.sin(tyaw)
            remain = (tx - self.x) * cx + (ty - self.y) * cy

            if abs(remain) < 0.08 or self._step_t >= timeout:
                if abs(remain) >= 0.08 and not self._dock_warned:
                    self._dock_warned = True
                    self.node.get_logger().warn(
                        f"{self.vid} dock 시간초과 — {remain:+.2f} m 남음")
                self.v = 0.0
                self.steer = 0.0
                # 남은 각도 오차는 천천히 좁힌다 (순간이동 금지)
                if self._slew_yaw(tyaw, dt) or self._step_t >= timeout + 3.0:
                    self._dock_warned = False
                    self._next_step()
                return (0.0, 0.0)

            # 조향은 오직 yaw 오차 보정용. 크게 틀지 못하도록 조인다.
            eyaw = math.atan2(math.sin(tyaw - self.yaw),
                              math.cos(tyaw - self.yaw))
            w = clamp(1.2 * eyaw, -0.25, 0.25)
            if remain < 0:
                w = -w               # 후진 중에는 보정 방향이 반대
            v = math.copysign(min(0.6, max(0.25, abs(remain))), remain)
            return (v, w)

        if kind == "reverse":
            _, dur = step
            if self._step_t >= dur:
                self._next_step()
                return (0.0, 0.0)
            return (-self.p["max_speed"] * 0.4, 0.0)

        if kind == "face":
            # Rotate the vehicle to a target yaw in place, over a few frames so
            # it looks smooth rather than snapping. A real rear-steer forklift
            # cannot spin in place, so this is a demo-only "square up to the
            # rack" flourish - used just before placing, not during travel.
            _, target = step
            err = math.atan2(math.sin(target - self.yaw), math.cos(target - self.yaw))
            if self._slew_yaw(target, dt, rate=1.0):
                self._next_step()
            return (0.0, 0.0)

        if kind == "fork":
            want = clamp(step[1], self.p["lift_min"], self.p["lift_max"])
            if abs(want - self._lift_target) > 1e-3:
                self.node.get_logger().info(
                    f"{self.vid} fork 스텝 {self._lift_target:.2f} -> {want:.2f}")
            self._lift_target = want
            timeout = step[2] if len(step) > 2 else 15.0
            if abs(self.lift_height - self._lift_target) < 0.005:
                self._next_step()
            elif self._step_t >= timeout:
                self.node.get_logger().warn(
                    f"{self.vid} fork 시간초과 — {self.lift_height:.2f} / "
                    f"{self._lift_target:.2f} m, 진행")
                self.lift_height = self._lift_target
                self._next_step()
            return (0.0, 0.0)

        if kind == "pick":
            self.pick(step[1], step[2], force=True)
            self._next_step()
            return (0.0, 0.0)

        if kind == "align":
            # 전진·후진을 반복하며 각도를 맞춘다 (실제 지게차의 전후진 정렬).
            #
            # 자전거 모델에서 조향을 유지한 채 전진/후진하면 회전 방향이 같다.
            #   전진: yaw_rate = +w      후진: yaw_rate = +w  (조향이 반대로 걸리므로)
            # 그래서 앞뒤로 같은 시간만 오가면 위치는 거의 그대로 두고 각도만
            # 보정된다. face 처럼 순간 회전시키지 않아 자연스럽다.
            _, target = step[0], step[1]
            timeout = step[2] if len(step) > 2 else 8.0
            err = math.atan2(math.sin(target - self.yaw),
                             math.cos(target - self.yaw))

            if abs(err) < 0.03:                 # 약 1.7도 이내면 충분
                self.v = 0.0
                self._next_step()               # 순간이동 없이 그대로 종료
                return (0.0, 0.0)
            if self._step_t >= timeout:
                # 전후진으로 못 맞춘 나머지는 천천히 돌려서 없앤다.
                # 예전에는 여기서 yaw 를 한 번에 대입해 순간이동처럼 보였다.
                self.v = 0.0
                if self._slew_yaw(target, dt) or self._step_t >= timeout + 4.0:
                    self.node.get_logger().info(
                        f"{self.vid} 정렬 완료 (남은 오차 "
                        f"{math.degrees(err):.1f}도)")
                    self._next_step()
                return (0.0, 0.0)

            # 0.7초마다 전진 <-> 후진 전환
            # 전환을 자주(0.5초), 속도는 낮게(0.3) — 흔들리며 밀리는 거리를 줄인다
            forward = int(self._step_t / 0.5) % 2 == 0
            speed = 0.3 * (1.0 if forward else -1.0)
            turn = clamp(err * 2.2, -1.0, 1.0)   # 오차에 비례한 회전
            return (speed, turn)

        if kind == "settle":
            # 차가 완전히 멈출 때까지 기다린다.
            # drive 스텝은 목표 반경에 들어오면 끝나는데, 그 순간에도 속도가
            # 남아 있어 곧바로 화물을 놓으면 미끄러지는 중에 떨어진 것처럼
            # 보인다. 속도가 0 에 가까워질 때까지 정지 명령을 유지한다.
            _, timeout = step if len(step) > 1 else ("settle", 2.0)
            if abs(self.v) < 0.02 or self._step_t >= timeout:
                self.v = 0.0
                self.steer = 0.0
                self._next_step()
            return (0.0, 0.0)

        if kind == "release":
            # 지금 있는 자리에 그대로 두고 추종만 끊는다.
            # place 는 지정 좌표로 순간이동시키지만, release 는 포크가 실제로
            # 놓은 위치에 화물을 남긴다 — 정확히 멈춘 그 자리에서 분리된다.
            if self.cargo is not None or self.cargo_id is not None:
                self.node.get_logger().info(
                    f"{self.vid} released {self.cargo_id} in place")
            self.cargo = None
            self.cargo_id = None
            self.cargo_size = None
            self._next_step()
            return (0.0, 0.0)

        if kind == "place":
            # step = ("place", x, y, z, yaw). Use the RACK's yaw, not the
            # vehicle's — a forklift parked slightly askew (rear-steer alignment
            # error) must still drop the pallet square to the shelf.
            place_yaw = step[4] if len(step) > 4 else 0.0
            self.place(step[1], step[2], step[3], yaw=place_yaw)
            self._next_step()
            return (0.0, 0.0)

        self._next_step()
        return (0.0, 0.0)

    def _slew_yaw(self, target, dt, rate=0.7):
        """남은 각도 오차를 순간이동 없이 제한 속도로 좁힌다.

        예전에는 self.yaw = target 으로 한 번에 맞췄는데, 화면에서 차가
        순간이동한 것처럼 보였다. 초당 rate(rad) 이하로만 돌린다.
        반환값: 목표에 도달했는가.
        """
        err = math.atan2(math.sin(target - self.yaw),
                         math.cos(target - self.yaw))
        if abs(err) < 0.015:
            return True
        self.yaw += clamp(err, -rate * dt, rate * dt)
        self.yaw = math.atan2(math.sin(self.yaw), math.cos(self.yaw))
        return False

    def _next_step(self):
        done = self._steps.pop(0)
        self._step_t = 0.0
        if self._steps:
            self.node.get_logger().info(
                f"{self.vid} 스텝 {done[0]} 완료 -> {self._steps[0][0]} "
                f"(남은 {len(self._steps)})")
        else:
            self.work_state = ""
            self.node.get_logger().info(f"{self.vid} mission complete")

    def _drive_toward(self, tx, ty, tol=0.15):
        """Simple pursuit toward a point. Returns (arrived, (cmd_v, cmd_w)).

        This is a stand-in for Nav2 so the sequence works before Nav2 is up.
        When Nav2 is running, replace this with a goal_pose send + arrival check.
        """
        dx, dy = tx - self.x, ty - self.y
        dist = math.hypot(dx, dy)
        if dist < tol:
            return True, (0.0, 0.0)

        # Heading error to the target, wrapped to -pi..pi.
        err = math.atan2(dy, dx) - self.yaw
        err = math.atan2(math.sin(err), math.cos(err))

        # 목표가 뒤에 있으면 후진한다. 전진만 하면 좁은 통로에서 방향을
        # 바꾸려고 원을 그리며 영영 도착하지 못한다(랙 앞에서 빙글빙글).
        if abs(err) > 1.9 and dist < 6.0:
            back = math.atan2(math.sin(err + math.pi),
                              math.cos(err + math.pi))
            v = -self.p["max_speed"] * min(1.0, max(0.25, dist)) * 0.7
            return False, (v, -2.0 * back)

        # Slow down near the target and when the heading error is large.
        # 하한이 없으면 목표 10 cm 앞에서 속도가 0.1배로 줄어 기어가고,
        # 허용 반경 안에 영영 못 들어간다(스텝이 끝나지 않는다).
        v = self.p["max_speed"] * min(1.0, max(0.25, dist)) \
            * max(0.2, math.cos(err))
        w = 2.0 * err                       # steer proportional to heading error
        return False, (v, w)

    def _integrate(self, cmd_v, cmd_w, dt):
        p = self.p

        # 1) Speed, with an acceleration limit so it does not jump.
        target_v = clamp(cmd_v, -p["max_speed"], p["max_speed"])
        dv = clamp(target_v - self.v, -p["max_accel"] * dt, p["max_accel"] * dt)
        self.v += dv

        # 2) Convert the commanded angular rate into a steering angle.
        #    Routing it through the wheelbase is the whole point: adding cmd_w
        #    straight onto yaw would give a differential-drive robot that can
        #    spin in place, which a forklift cannot do.
        #
        #    Use the SIGNED speed, not abs(). A bicycle turns the opposite way
        #    when reversing for the same steering angle. Nav2's cmd_w is the
        #    desired yaw rate in the world; steer = atan(w*L / v). With abs(v)
        #    the steering did not flip on reverse, so "back up and turn left"
        #    steered the same way as forward and the truck oscillated
        #    forward/back in place instead of backing into the turn.
        if abs(self.v) > 1e-4:
            # atan(w*L / |v|) gives the geometric steering magnitude+side for
            # forward motion. When reversing, the same steer produces the
            # opposite turn, so flip the sign. (Using atan2(.., v) directly
            # would wrap past the steering limit and clamp wrong.)
            target_steer = math.atan(cmd_w * p["wheelbase"] / abs(self.v))
            if self.v < 0.0:
                target_steer = -target_steer
        else:
            target_steer = 0.0
        target_steer = clamp(target_steer, -p["max_steer"], p["max_steer"])

        # Servo response limit - the steering cannot snap instantly.
        ds = clamp(target_steer - self.steer,
                   -p["max_steer_rate"] * dt, p["max_steer_rate"] * dt)
        self.steer += ds

        # 3) Bicycle model integration. With v == 0 the yaw does not change, so
        #    "no spinning in place" falls out of the maths.
        self.yaw += (self.v / p["wheelbase"]) * math.tan(self.steer) * dt
        self.yaw = math.atan2(math.sin(self.yaw), math.cos(self.yaw))  # wrap to -pi..pi
        self.x += self.v * math.cos(self.yaw) * dt
        self.y += self.v * math.sin(self.yaw) * dt

    # -- Fork ----------------------------------------------------------------
    def _update_lift(self, dt):
        if self.lift is None or self._lift_base is None:
            return
        # Rate limited so the fork travels at the stepper's real speed instead
        # of teleporting to the target.
        step = self.p["lift_rate"] * dt
        self.lift_height += clamp(self._lift_target - self.lift_height, -step, step)

        lx, ly, lz = self._lift_base
        if self.p["lift_axis"] == 0:
            lx += self.lift_height
        elif self.p["lift_axis"] == 1:
            ly += self.lift_height
        else:
            lz += self.lift_height

        # 포크는 차체와 같은 방법(set_world_pose)으로 옮긴다.
        #
        # 이 프림은 에셋의 물리 프림이라 재생 중 Fabric 이 매 프레임 덮어쓴다.
        # set_local_pose 도, 순수 USD 쓰기(XformCommonAPI)도 화면에 반영되지
        # 않았다 — lift_height 만 오르고 프림 월드 z 는 그대로였다.
        # Isaac 래퍼의 set_world_pose 는 Fabric 에 직접 쓰므로 반영된다.
        # (차체가 그 방식이라 잘 움직였다)
        #
        # 로컬 오프셋을 차량 자세로 회전시켜 월드 좌표를 직접 만든다.
        c, s_ = math.cos(self.yaw), math.sin(self.yaw)
        try:
            base_z = float(self.prim.get_world_pose()[0][2])
        except Exception:
            base_z = 0.0
        wx = self.x + lx * c - ly * s_
        wy = self.y + lx * s_ + ly * c
        wz = base_z + lz
        try:
            self.lift.set_world_pose(
                position=(wx, wy, wz),
                orientation=yaw_to_quat_usd(self.yaw))
        except Exception as e:
            if not getattr(self, "_lift_warned", False):
                self.node.get_logger().warn(f"{self.vid} 포크 이동 실패: {e}")
                self._lift_warned = True

    # -- Cargo ---------------------------------------------------------------
    def fork_tip_pose(self):
        """World pose of the point where a pallet sits on the forks."""
        fx = self.p["fork_offset_x"]
        fz = self.p["fork_offset_z"] + self.lift_height
        return (
            self.x + fx * math.cos(self.yaw),
            self.y + fx * math.sin(self.yaw),
            fz,
            self.yaw,
        )

    def can_pick(self, cargo_prim_path):
        """Whether the forks are lined up well enough to enter the pallet.

        The tolerance mirrors the real hardware allowance (~+/-1.2cm). Making it
        looser than reality would mean a pickup that works in sim and jams on
        the bench.
        """
        cpos, _ = XFormPrim(cargo_prim_path).get_world_pose()
        fx, fy, fz, _ = self.fork_tip_pose()
        dist = math.hypot(float(cpos[0]) - fx, float(cpos[1]) - fy)
        height_err = abs(float(cpos[2]) - fz)
        return dist <= self.p["pick_xy_tol"] and height_err <= self.p["pick_z_tol"]

    def pick(self, cargo_prim_path, cargo_id=None, force=False):
        if self.cargo is not None:
            return False
        if not force and not self.can_pick(cargo_prim_path):
            return False
        # Isaac 코어 래퍼(XFormPrim) 대신 순수 USD 프림을 잡는다. 코어 래퍼는
        # 물리 뷰 등록 등 부가 작업이 있어 재생 중에 무겁고, 화물은 매 프레임
        # 움직이므로 그 비용이 그대로 프레임 시간에 실린다.
        import omni.usd as _omni_usd
        self.cargo = _omni_usd.get_context().get_stage().GetPrimAtPath(
            cargo_prim_path)
        if not self.cargo.IsValid():
            self.cargo = None
            self.node.get_logger().warn(f"cargo prim 없음: {cargo_prim_path}")
            return False
        self.cargo_id = cargo_id or cargo_prim_path.rsplit("/", 1)[-1]
        # 크기는 cargo_demo 가 set_cargo_size() 로 채운다. 관제 화면에서
        # "이 차가 무엇을 싣고 있는지" 를 보여주려면 telemetry 에 실려야 한다.
        self.node.get_logger().info(f"{self.vid} picked {self.cargo_id}")
        return True

    def place(self, x, y, z, yaw=0.0):
        """Release the load at an exact pose - a rack slot from layout.json.

        A real forklift lowers until the pallet rests on the rack, then reverses
        out; it never drops. Snapping to the slot pose is the same end state
        without the settling wobble a physics drop would produce.
        """
        if self.cargo is None:
            return False
        self._set_cargo_pose(x, y, z, yaw)
        self.node.get_logger().info(f"{self.vid} placed {self.cargo_id}")
        self.cargo = None
        self.cargo_id = None
        return True

    def _update_cargo(self):
        """Carried load tracks the fork tip. Runs after the pose and lift update
        so it uses this frame's values."""
        if self.cargo is None:
            return
        fx, fy, fz, fyaw = self.fork_tip_pose()
        self._set_cargo_pose(fx, fy, fz, fyaw)

    def _set_cargo_pose(self, x, y, z, yaw):
        """화물 프림을 순수 USD API 로 옮긴다.

        /World/Cargo 는 변환이 없는 Xform 이므로 로컬 = 월드 좌표다.
        """
        from pxr import UsdGeom
        api = UsdGeom.XformCommonAPI(self.cargo)
        api.SetTranslate((float(x), float(y), float(z)))
        api.SetRotate((0.0, 0.0, math.degrees(yaw)))

    # -- Transforms ----------------------------------------------------------
    def _ensure_prim(self):
        """Return True if the vehicle prim is usable, re-grabbing if needed.

        The cached prim handle expires whenever the stage is rebuilt (Stop/Play,
        reopening the scene, adding markers or cargo). When that happens we grab
        a fresh handle from the same path. If the prim genuinely does not exist
        yet, return False so the caller skips this frame instead of throwing.
        """
        try:
            if self.prim.is_valid():
                return True
        except Exception:
            pass
        # Handle expired or invalid — try to re-grab from the path.
        try:
            self.prim = XFormPrim(self.prim_path)
            return self.prim.is_valid()
        except Exception:
            return False

    def _apply_prim(self):
        # Caller (update) already ensured the prim is valid this frame.
        qx, qy, qz, qw = yaw_to_quat(self.yaw)
        _, _, z = self.prim.get_world_pose()[0]   # keep the authored height
        self.prim.set_world_pose(
            position=(self.x, self.y, z),
            orientation=(qw, qx, qy, qz),          # USD order is (w, x, y, z)
        )

    def set_work_state(self, s):
        """LOADING / UNLOADING / "" (해제). telemetry 의 state 를 덮어쓴다."""
        self.work_state = str(s or "")

    def set_cargo_size(self, w, d, h):
        """싣고 있는 화물의 크기를 기록한다 (telemetry 로 나간다)."""
        self.cargo_size = {"w": round(float(w), 3),
                           "d": round(float(d), 3),
                           "h": round(float(h), 3)}

    def set_mirror_pose(self, x, y, yaw):
        """실물 좌표를 받아 둔다. ROS 콜백(다른 스레드)에서 불러도 안전하다.

        USD 를 여기서 건드리면 스테이지 락에서 데드락이 나 Isaac 이 멈춘다.
        값만 저장하고 실제 반영은 메인 스레드의 update() 가 한다.
        """
        with self._mirror_lock:
            self._mirror_pose = (float(x), float(y), float(yaw))

    def _publish_state(self, dt):
        """상태 요약을 10Hz 로 /{ns}/state 에 JSON 문자열로 낸다.

        MQTT 브릿지가 이 값에 vehicleId/ts 만 붙여 telemetry 로 보낸다.
        포크 높이와 적재 여부는 이 노드 밖에서는 알 수 없어 여기서 실어야 한다.
        """
        self._state_t += dt
        if self._state_t < 0.1:
            return
        self._state_t = 0.0

        if self._steps:
            step = self._steps[0][0]
            if step == "fork":
                state = "LIFTING" if self._lift_target > self.lift_height \
                    else "LOWERING"
            elif step in ("pick", "place"):
                state = "LIFTING"
            else:
                state = "MOVING"
        elif abs(self.v) > 0.02:
            state = "MOVING"
        else:
            state = "IDLE"

        # 적재/하역 작업 중이면 그 사실이 더 중요하다.
        # (바이 정렬처럼 겉보기엔 MOVING 이지만 실제로는 적재 절차 중이다)
        if self.work_state:
            state = self.work_state

        payload = {
            "pose": {"x": round(self.x, 3),
                     "y": round(self.y, 3),
                     "yaw": round(self.yaw, 4)},
            "velocity": {
                "linear": round(self.v, 3),
                "angular": round(
                    (self.v / self.p["wheelbase"]) * math.tan(self.steer), 4),
            },
            "forkHeight": round(self.lift_height, 3),
            # 화물이 포크의 자식 프림이면 v.cargo 는 비어 있다. 식별자로 판단한다.
            "loaded": self.cargo_id is not None,
            # 스텝 머신이 돌고 있는가. 관제(데모)가 이걸 보고 기다린다.
            # 안 보고 넘어가면 다음 명령이 적재 스텝을 덮어써서 화물을
            # 못 내려놓는다.
            "mission": bool(self._steps),
            "step": self._steps[0][0] if self._steps else "",
            "cargoId": self.cargo_id,
            # 싣고 있는 화물의 크기 (시뮬 단위, 실물은 ÷10).
            # 없으면 null. 관제 화면이 화물 종류를 표시하는 데 쓴다.
            "cargo": (dict(self.cargo_size, id=self.cargo_id)
                      if self.cargo_size else None),
            "state": state,
        }
        self.state_pub.publish(String(data=json.dumps(payload)))

    def _publish_odom(self):
        now = self.node.get_clock().now().to_msg()
        qx, qy, qz, qw = yaw_to_quat(self.yaw)

        odom = Odometry()
        odom.header.stamp = now
        odom.header.frame_id = self.frame_odom
        odom.child_frame_id = self.frame_base
        odom.pose.pose.position.x = self.x
        odom.pose.pose.position.y = self.y
        odom.pose.pose.orientation.x = qx
        odom.pose.pose.orientation.y = qy
        odom.pose.pose.orientation.z = qz
        odom.pose.pose.orientation.w = qw
        # twist is expressed in child_frame (base_link). A forklift cannot move
        # sideways, so linear.y stays zero.
        odom.twist.twist.linear.x = self.v
        odom.twist.twist.angular.z = (self.v / self.p["wheelbase"]) * math.tan(self.steer)
        self.odom_pub.publish(odom)

    def odom_tf(self):
        now = self.node.get_clock().now().to_msg()
        qx, qy, qz, qw = yaw_to_quat(self.yaw)
        t = TransformStamped()
        t.header.stamp = now
        t.header.frame_id = self.frame_odom
        t.child_frame_id = self.frame_base
        t.transform.translation.x = self.x
        t.transform.translation.y = self.y
        t.transform.rotation.x = qx
        t.transform.rotation.y = qy
        t.transform.rotation.z = qz
        t.transform.rotation.w = qw
        return t

    def static_tfs(self):
        """base_link -> sensors. Fixed offsets, published once."""
        out = []
        for name, (sx, sy, sz) in SENSOR_TF.items():
            t = TransformStamped()
            t.header.stamp = self.node.get_clock().now().to_msg()
            t.header.frame_id = self.frame_base
            t.child_frame_id = f"{self.vid}_{name}"
            t.transform.translation.x = sx
            t.transform.translation.y = sy
            t.transform.translation.z = sz
            t.transform.rotation.w = 1.0
            out.append(t)
        return out


class VehicleFleet(Node):
    """Holds every vehicle in one node. Adding vehicles does not change the code."""

    def __init__(self):
        # Use simulation time so the stamps on /odom and /tf match the lidar and
        # /clock coming from Isaac. Passed at construction (not set afterwards)
        # so it takes effect before the first message is stamped. Without it the
        # node stamps with wall-clock time, the stamps run ahead of the sim-time
        # transforms, and AMCL / the costmaps drop every scan with "timestamp
        # earlier than the transform cache" - so map->odom is never produced.
        from rclpy.parameter import Parameter
        super().__init__(
            "kinematic_fleet",
            parameter_overrides=[Parameter("use_sim_time", Parameter.Type.BOOL, True)],
        )

        self.vehicles = {}

        self.tf_pub = self.create_publisher(TFMessage, "/tf", 10)
        # Static TF must reach subscribers that connect later, hence
        # TRANSIENT_LOCAL durability.
        self.tf_static_pub = self.create_publisher(
            TFMessage, "/tf_static",
            QoSProfile(depth=1, durability=QoSDurabilityPolicy.TRANSIENT_LOCAL))

        self._sub = (
            omni.kit.app.get_app()
            .get_update_event_stream()
            .create_subscription_to_pop(self._on_update, name="kinematic_fleet")
        )
        # Set _running before starting the thread. The other order lets the
        # thread read an attribute that does not exist yet.
        self._running = True
        self._spin_thread = threading.Thread(target=self._spin, daemon=True)
        self._spin_thread.start()
        self.get_logger().info("kinematic fleet started")

    def spawn(self, vehicle_id, prim_path, spawn=(0.0, 0.0, 0.0), params=None,
              lift_prim=None):
        v = KinematicVehicle(self, vehicle_id, prim_path, params, spawn, lift_prim)
        self.vehicles[vehicle_id] = v
        # Publish the FULL set of static TFs for EVERY vehicle. The latched
        # publisher has depth=1, so publishing only the new vehicle's TFs would
        # overwrite the previously latched message, and late-joining subscribers
        # (Nav2 costmaps) would receive only the last vehicle's base_link->laser
        # TF. The costmap then cannot project the other vehicle's scan and
        # silently drops every ray -> obstacles never marked -> drives through.
        self.republish_static()
        self.get_logger().info(f"spawned {vehicle_id} at {spawn}")
        return v

    def republish_static(self):
        """Latch base_link->sensor TFs for every vehicle in one message."""
        all_static = []
        for veh in self.vehicles.values():
            all_static.extend(veh.static_tfs())
        self.tf_static_pub.publish(TFMessage(transforms=all_static))

    def _on_update(self, event):
        dt = event.payload["dt"]
        if dt <= 0.0:
            return
        for v in self.vehicles.values():
            v.update(dt)
        if self.vehicles:
            self.tf_pub.publish(TFMessage(
                transforms=[v.odom_tf() for v in self.vehicles.values()]))

    def _spin(self):
        while self._running:
            rclpy.spin_once(self, timeout_sec=0.05)

    def stop(self):
        """앱 업데이트 구독과 스핀 스레드를 확실히 끊는다.

        하나라도 남으면 그 fleet 이 계속 프림을 쓰기 때문에, 새 fleet 과
        섞여 차량이 명령 없이 움직인다. 여러 번 불려도 안전하다.
        """
        self._running = False
        try:
            if self._sub is not None:
                self._sub.unsubscribe()
        except Exception:
            pass
        self._sub = None
        self.vehicles = {}
        try:
            self.destroy_node()
        except Exception:
            pass
        print("kinematic fleet stopped")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if not rclpy.ok():
    rclpy.init()

# 이 파일을 다시 exec 하면 새 fleet 이 만들어진다. 그런데 옛 fleet 은
# 앱 업데이트 구독(_sub)과 스핀 스레드가 그대로 살아 있어서, 같은 프림을
# 매 프레임 함께 쓰고 cmd_vel 도 같이 구독한다. 그러면
#   - 아무 명령을 안 줘도 차량이 움직이고
#   - /state, /tf 가 두 벌씩 나가며
#   - 포크·화물이 제멋대로 움직인다.
# 그래서 새로 만들기 전에 옛 것을 반드시 정리한다.
try:
    fleet.stop()
    print("이전 fleet 정리됨 (재실행)")
except NameError:
    pass
except Exception as _e:
    print(f"이전 fleet 정리 실패(무시): {_e}")

fleet = VehicleFleet()
print(f"kinematic_vehicle v{VEHICLE_VERSION} 로드됨")

# Spawn points belong in layout.json (shared with the backend), but the scene
# layout is not fixed yet, so they are inline for now.
fleet.spawn(
    "SIM_F02",
    "/World/Forklift_SIM_F02",
    # (3, 2) corner, facing +X (yaw 0) into the warehouse. spawn=(x, y, yaw).
    # Must match nav2_sim.yaml initial_pose and the prim's Translate/Rotate.
    spawn=(3.0, 2.0, 0.0),
    # Path to the prim that carries the forks. VERIFY in the scene: click the
    # forklift, expand it, find the lift/mast child, and copy its exact path
    # here - ForkliftC's internal name may differ from this guess. Then drag its
    # local Translate to check which axis moves the fork and set lift_axis above.
    # Omit this argument entirely if you cannot find a separate fork prim yet.
    # The real fork/mast is under forklift_c/lift (contains Fork01/02 meshes).
    # /World/Forklift_SIM_F02/lift is an empty placeholder Xform - driving that
    # moves nothing visible.
    lift_prim="/World/Forklift_SIM_F02/forklift_c/lift",
)

# Stop with: fleet.stop()

# ---------------------------------------------------------------------------
# Parameter calibration (redo whenever the asset changes)
#
# odom tells you how the vehicle actually moved, so back the numbers out of it -
# more accurate than measuring by hand because it includes real motion.
#
#   1) command it:  ros2 topic pub /sim_f02/cmd_vel geometry_msgs/msg/Twist \
#                     "{linear: {x: 0.2}, angular: {z: 0.067}}" -r 10
#   2) read it:     ros2 topic echo /sim_f02/odom --once
#
#   3) wheel_radius (speed) first - speed must be right before turning can be:
#        actual_speed = hypot(twist.linear.x, twist.linear.y)
#   4) wheelbase (turning):
#        actual_radius = actual_speed / twist.angular.z
#        steer = atan(wheelbase * cmd_w / cmd_v)
#        wheelbase_new = actual_radius * tan(steer)
#
# Guard: if the commanded radius (cmd_v / cmd_w) is smaller than the minimum
# turning radius, the steering saturates and no calibration will match.
# ---------------------------------------------------------------------------
