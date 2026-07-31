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
    "lift_axis": 2,         # which local axis moves the fork: 0=X, 1=Y, 2=Z
    # Where a pallet rests on the forks, relative to base_link.
    "fork_offset_x": 1.8,   # forward of base_link (m) - 0.18 * 10
    "fork_offset_z": 0.1,   # above the ground at lift_min (m) - 0.01 * 10
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

        # Mission queue. Each entry is a step the vehicle works through over
        # many frames (drive there, lower fork, pick, raise, drive to rack,
        # place, reverse out). Empty queue = idle / cmd_vel controlled.
        self._steps = []
        self._step_t = 0.0       # time spent in the current step

        self._apply_prim()

    # -- ROS callbacks (separate thread) ------------------------------------
    def _on_cmd_vel(self, msg):
        with self._cmd_lock:
            self._cmd_v = msg.linear.x
            self._cmd_w = msg.angular.z
        self._last_cmd_time = self.node.get_clock().now()

    def _on_fork_cmd(self, msg):
        self._lift_target = clamp(msg.data, self.p["lift_min"], self.p["lift_max"])

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

        self._integrate(cmd_v, cmd_w, dt)
        self._apply_prim()
        self._update_lift(dt)
        self._update_cargo()
        self._publish_odom()

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

        if kind == "drive":
            _, tx, ty = step
            done, cmd = self._drive_toward(tx, ty)
            if done:
                self._next_step()
            return cmd

        if kind == "reverse":
            _, dur = step
            if self._step_t >= dur:
                self._next_step()
                return (0.0, 0.0)
            return (-self.p["max_speed"] * 0.4, 0.0)

        if kind == "fork":
            self._lift_target = clamp(step[1], self.p["lift_min"], self.p["lift_max"])
            if abs(self.lift_height - self._lift_target) < 0.005:
                self._next_step()
            return (0.0, 0.0)

        if kind == "pick":
            self.pick(step[1], step[2], force=True)
            self._next_step()
            return (0.0, 0.0)

        if kind == "place":
            self.place(step[1], step[2], step[3], yaw=self.yaw)
            self._next_step()
            return (0.0, 0.0)

        self._next_step()
        return (0.0, 0.0)

    def _next_step(self):
        self._steps.pop(0)
        self._step_t = 0.0
        if not self._steps:
            self.node.get_logger().info(f"{self.vid} mission complete")

    def _drive_toward(self, tx, ty, tol=0.08):
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

        # Slow down near the target and when the heading error is large.
        v = self.p["max_speed"] * min(1.0, dist) * max(0.2, math.cos(err))
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
        if self.lift is None:
            return
        # Rate limited so the fork travels at the stepper's real speed instead
        # of teleporting to the target.
        step = self.p["lift_rate"] * dt
        self.lift_height += clamp(self._lift_target - self.lift_height, -step, step)

        pos = list(self._lift_base)
        pos[self.p["lift_axis"]] = self._lift_base[self.p["lift_axis"]] + self.lift_height
        self.lift.set_local_pose(translation=tuple(pos))

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
        self.cargo = XFormPrim(cargo_prim_path)
        self.cargo_id = cargo_id or cargo_prim_path.rsplit("/", 1)[-1]
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
        self.cargo.set_world_pose(position=(x, y, z), orientation=yaw_to_quat_usd(yaw))
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
        self.cargo.set_world_pose(position=(fx, fy, fz), orientation=yaw_to_quat_usd(fyaw))

    # -- Transforms ----------------------------------------------------------
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
    # (3, 2) corner, facing +X (yaw 0) into the warehouse. spawn=(x, y, yaw).
    # Must match nav2_sim.yaml initial_pose and the prim's Translate/Rotate.
    spawn=(3.0, 2.0, 0.0),
    # Path to the prim that carries the forks. VERIFY in the scene: click the
    # forklift, expand it, find the lift/mast child, and copy its exact path
    # here - ForkliftC's internal name may differ from this guess. Then drag its
    # local Translate to check which axis moves the fork and set lift_axis above.
    # Omit this argument entirely if you cannot find a separate fork prim yet.
    lift_prim="/World/Forklift_SIM_F02/lift",
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
