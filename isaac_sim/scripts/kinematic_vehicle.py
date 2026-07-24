"""
Kinematic simulated forklift (runs inside Isaac Sim).

Instead of the physics engine, this integrates a bicycle model and writes the
prim transform directly. The goal is to reproduce the behaviour of the real
miniature hardware regardless of how the Isaac asset is rigged.

Responsibilities
    sub   /{ns}/cmd_vel        (geometry_msgs/Twist)      <- Nav2
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

import math
import threading

import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, QoSDurabilityPolicy
from geometry_msgs.msg import Twist, TransformStamped
from nav_msgs.msg import Odometry
from std_msgs.msg import Float32
from tf2_msgs.msg import TFMessage

# The import path depends on the Isaac Sim version.
#   <= 4.0 : from omni.isaac.core.prims import XFormPrim
#   >= 4.5 : from isaacsim.core.prims import SingleXFormPrim as XFormPrim
from omni.isaac.core.prims import XFormPrim
import omni.kit.app


# ---------------------------------------------------------------------------
# Vehicle parameters
#
# These describe the miniature hardware. The twin scene is built at miniature
# 1:1 scale, so measurements from C (embedded) go in as-is - no 1/10 conversion.
# The values below are assumptions made before the hardware exists; replace them
# once the real forklift is measured.
# ---------------------------------------------------------------------------
DEFAULT_PARAMS = {
    "wheelbase": 0.16,      # front axle to rear axle (m)
    "max_steer": 0.5,       # max steering angle (rad, ~29 deg)
    "max_speed": 0.3,       # max forward speed (m/s)
    "max_accel": 0.5,       # max acceleration (m/s^2) - prevents jerky starts
    "max_steer_rate": 3.0,  # max steering rate (rad/s) - servo response limit
    # Fork (FR-303). The stepper is slow, so the rate limit matters visually.
    "lift_min": 0.0,        # lowest fork position (m, local to the lift prim)
    "lift_max": 0.15,       # highest fork position (m)
    "lift_rate": 0.05,      # lift speed (m/s)
    "lift_axis": 2,         # which local axis moves the fork: 0=X, 1=Y, 2=Z
    # Where a pallet rests on the forks, relative to base_link.
    "fork_offset_x": 0.18,  # forward of base_link (m)
    "fork_offset_z": 0.01,  # above the ground at lift_min (m)
    # Pickup tolerance. Matches the real allowance from widening the pallet
    # openings to 3.5-4cm (spec: fork-pallet alignment note).
    "pick_xy_tol": 0.012,
    "pick_z_tol": 0.02,
}
# min turning radius = wheelbase / tan(max_steer) = 0.16 / tan(0.5) = 0.293 m
# This value goes straight into the Nav2 config (hand to D) and is also what
# determines the minimum aisle width in the warehouse layout.

# Sensor mounting positions relative to base_link (m), measured in the scene.
SENSOR_TF = {
    "laser":     (0.00,  0.00, 0.12),
    "tof_left":  (0.14,  0.05, 0.03),
    "tof_right": (0.14, -0.05, 0.03),
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
        self.prim = XFormPrim(prim_path)
        self.p = dict(DEFAULT_PARAMS, **(params or {}))

        # Fork. The lift prim is a child of the vehicle, so it must be driven in
        # LOCAL coordinates - set_world_pose would detach it from the vehicle and
        # leave the forks behind when the truck drives off.
        self.lift = XFormPrim(lift_prim) if lift_prim else None
        self.lift_height = self.p["lift_min"]
        self._lift_target = self.p["lift_min"]
        self._lift_base = None
        if self.lift is not None:
            self._lift_base = list(self.lift.get_local_pose()[0])
            node.create_subscription(
                Float32, f"/{self.ns}/fork_cmd", self._on_fork_cmd, 10)

        # Cargo. Carried loads follow the fork by having their world pose
        # rewritten every frame. Reparenting inside the USD stage mid-simulation
        # is the other option but it is disruptive and harder to undo.
        self.cargo = None        # XFormPrim currently carried
        self.cargo_id = None     # id reported over MQTT as cargoId

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
        node.create_subscription(
            Twist, f"/{self.ns}/cmd_vel", self._on_cmd_vel, 10)

        self._apply_prim()

    # -- ROS callback (separate thread) -------------------------------------
    def _on_cmd_vel(self, msg):
        with self._cmd_lock:
            self._cmd_v = msg.linear.x
            self._cmd_w = msg.angular.z
        self._last_cmd_time = self.node.get_clock().now()

    # -- Per frame (main thread) --------------------------------------------
    def update(self, dt):
        with self._cmd_lock:
            cmd_v, cmd_w = self._cmd_v, self._cmd_w

        # Stop if Nav2 dies or the link drops. On real hardware this is a crash.
        age = (self.node.get_clock().now() - self._last_cmd_time).nanoseconds * 1e-9
        if age > 0.5:
            cmd_v, cmd_w = 0.0, 0.0

        self._integrate(cmd_v, cmd_w, dt)
        self._apply_prim()
        self._update_lift(dt)
        self._update_cargo()
        self._publish_odom()

    # -- Fork ----------------------------------------------------------------
    def _on_fork_cmd(self, msg):
        self._lift_target = clamp(
            msg.data, self.p["lift_min"], self.p["lift_max"])

    def _update_lift(self, dt):
        if self.lift is None:
            return
        # Rate limited so the fork travels at the stepper's real speed instead
        # of teleporting to the target.
        step = self.p["lift_rate"] * dt
        self.lift_height += clamp(self._lift_target - self.lift_height, -step, step)

        pos = list(self._lift_base)
        pos[self.p["lift_axis"]] = self._lift_base[self.p["lift_axis"]] + self.lift_height
        self.lift.set_local_pose(translation=tuple(pos))

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
        if abs(self.v) > 1e-4:
            target_steer = math.atan2(cmd_w * p["wheelbase"], abs(self.v))
        else:
            target_steer = 0.0
        target_steer = clamp(target_steer, -p["max_steer"], p["max_steer"])

        # Servo response limit - the steering cannot snap instantly.
        ds = clamp(target_steer - self.steer,
                   -p["max_steer_rate"] * dt, p["max_steer_rate"] * dt)
        self.steer += ds

        # 3) Bicycle model integration.
        #    With v == 0 the yaw does not change either, so "no spinning in
        #    place" falls out of the maths instead of needing a special case.
        self.yaw += (self.v / p["wheelbase"]) * math.tan(self.steer) * dt
        self.yaw = math.atan2(math.sin(self.yaw), math.cos(self.yaw))  # wrap to -pi..pi
        self.x += self.v * math.cos(self.yaw) * dt
        self.y += self.v * math.sin(self.yaw) * dt

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

        The tolerance mirrors the real hardware: with 3.5-4cm pallet openings
        and 1cm forks the allowance is about +/-1.2cm (see the spec note on
        fork-pallet alignment). Making this looser than the real thing would
        mean a pickup that works in simulation and jams on the bench.
        """
        cargo = XFormPrim(cargo_prim_path)
        cpos, _ = cargo.get_world_pose()
        fx, fy, fz, fyaw = self.fork_tip_pose()

        dist = math.hypot(float(cpos[0]) - fx, float(cpos[1]) - fy)
        height_err = abs(float(cpos[2]) - fz)
        return dist <= self.p["pick_xy_tol"] and height_err <= self.p["pick_z_tol"]

    def pick(self, cargo_prim_path, cargo_id=None, force=False):
        if self.cargo is not None:
            return False
        if not force and not self.can_pick(cargo_prim_path):
            return False
        self.cargo = XFormPrim(cargo_prim_path)
        self.cargo_id = cargo_id or cargo_prim_path.rsplit("/", 1)[-1]
        self.node.get_logger().info(f"{self.vid} picked {self.cargo_id}")
        return True

    def place(self, x, y, z, yaw=0.0):
        """Release the load at an exact pose - a rack slot from layout.json.

        A real forklift lowers the forks until the pallet rests on the rack and
        then reverses out; it never drops anything. Snapping to the slot pose is
        the same end state without the settling wobble a physics drop produces.
        """
        if self.cargo is None:
            return False
        self.cargo.set_world_pose(
            position=(x, y, z), orientation=yaw_to_quat_usd(yaw))
        self.node.get_logger().info(f"{self.vid} placed {self.cargo_id}")
        self.cargo = None
        self.cargo_id = None
        return True

    def _update_cargo(self):
        """Carried load tracks the fork tip. Runs after the pose and lift update
        so it uses this frame's values, not last frame's."""
        if self.cargo is None:
            return
        fx, fy, fz, fyaw = self.fork_tip_pose()
        self.cargo.set_world_pose(
            position=(fx, fy, fz), orientation=yaw_to_quat_usd(fyaw))

    def _apply_prim(self):
        qx, qy, qz, qw = yaw_to_quat(self.yaw)
        _, _, z = self.prim.get_world_pose()[0]   # keep the authored height
        self.prim.set_world_pose(
            position=(self.x, self.y, z),
            orientation=(qw, qx, qy, qz),          # USD order is (w, x, y, z)
        )

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
        odom.twist.twist.angular.z = (
            (self.v / self.p["wheelbase"]) * math.tan(self.steer))
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
        super().__init__("kinematic_fleet")
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
        self.tf_static_pub.publish(TFMessage(transforms=v.static_tfs()))
        self.get_logger().info(f"spawned {vehicle_id} at {spawn}")
        return v

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
        self._running = False
        self._sub.unsubscribe()
        self.destroy_node()
        print("kinematic fleet stopped")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
if not rclpy.ok():
    rclpy.init()

fleet = VehicleFleet()

# Spawn points belong in layout.json (shared with the backend), but the scene
# layout is not fixed yet, so they are inline for now.
fleet.spawn(
    "SIM_F02",
    "/World/Forklift_SIM_F02",
    spawn=(0.30, 0.20, 0.0),
    # Path to the prim that carries the forks. Check the axis and travel range
    # in the scene first (drag its local Translate and watch which way it goes),
    # then set lift_axis / lift_min / lift_max accordingly.
    lift_prim="/World/Forklift_SIM_F02/ForkliftC/lift",
)

# Stop with: fleet.stop()
