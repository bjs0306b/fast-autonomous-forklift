"""
Isaac Sim <-> MQTT twin bridge (Sprint 1 draft).

Two jobs:
  1) Read the simulated forklift pose and publish it to forklift/SIM_F01/location
  2) Subscribe to forklift/REAL_F01/location and drive the twin stand-in prim
     that mirrors the real miniature forklift

This does NOT go through ROS. Isaac runs Python in-process, so talking to the
broker directly is the shortest path. The ROS side (mqtt_client) lives on the
Orin and is owned by C/D.

Usage
    Paste into the Isaac Sim Script Editor and run. Stop with bridge.stop().

Prerequisite
    Install paho-mqtt into the Isaac Sim python:
    (Windows) <ISAAC_ROOT>\\python.bat -m pip install paho-mqtt
    (Linux)   <ISAAC_ROOT>/python.sh  -m pip install paho-mqtt
"""

import json
import math
import threading
from datetime import datetime

import paho.mqtt.client as mqtt

import rclpy
from rclpy.node import Node
from geometry_msgs.msg import Twist, PoseStamped

# The import path depends on the Isaac Sim version.
#   <= 4.0 : from omni.isaac.core.prims import XFormPrim
#   >= 4.5 : from isaacsim.core.prims import SingleXFormPrim as XFormPrim
from omni.isaac.core.prims import XFormPrim
import omni.kit.app

# ---------------------------------------------------------------------------
# Config - this block is the only part that should need editing
# ---------------------------------------------------------------------------
BROKER_HOST = "localhost"   # replace with the EC2 broker address
BROKER_PORT = 1883
#
# 🔴 STALE AS WRITTEN (checked 2026-08-06). This block predates the broker move.
#
#   The only broker in operation is now the EC2 one: TLS on 8883 with
#   `allow_anonymous false`. Plaintext 1883 has nothing listening on EC2, and the
#   old shared GPU-server broker (70.12.130.106:1883) was shut down on 2026-08-03.
#   So this script as-is can only talk to a broker you started yourself locally.
#
#   To point it at production you need THREE things, not just the host:
#     1) BROKER_PORT = 8883
#     2) client.tls_set(ca_certs=<infra/mqtt-ca.crt>, cert_reqs=ssl.CERT_REQUIRED)
#        plus client.tls_insecure_set(False)
#     3) client.username_pw_set(user, password)
#   Copy the policy from `ai/src/station/trigger.py` (MeasureTrigger._connect) or
#   `ros2_ws/src/fast_mqtt_bridge/fast_mqtt_bridge/mqtt_policy.configure_tls` —
#   both already do exactly this. Do not invent a third variant.
#
#   ⚠️ Skipping (2) or (3) does not fail loudly in an obvious way: the connect
#   attempt is refused by the broker and this bridge just never publishes, so the
#   twin sits still and looks like a pose problem.
#
# NOTE (still true): "localhost" only works when this script runs on the same host
# as the broker. A locally started Mosquitto listens on 127.0.0.1 / [::1] only, so
# Isaac Sim running in WSL, Docker, or on another PC cannot reach it with this
# value. See docs/backend-message/communication-protocol.md.

# These are MQTT identifiers and must match `vehicle.vehicle_id` in the database
# exactly. The DB rows are REAL-F01 / SIM-F01 with a HYPHEN; the backend drops
# messages whose vehicleId is not registered, so an underscore here means every
# published message is silently discarded.
# (The prim paths below keep underscores on purpose - those are USD scene paths,
#  not vehicle identifiers, and are unrelated to MQTT.)
SIM_ID = "SIM-F01"
REAL_ID = "REAL-F01"

SIM_PRIM_PATH = "/World/Forklift_SIM_F01"    # self-driving simulated vehicle
REAL_PRIM_PATH = "/World/Forklift_REAL_F01"  # stand-in that mirrors the real one

PUBLISH_HZ = 10.0     # the scene runs at 60fps but 10Hz is plenty for the HMI
STATUS_HZ = 1.0

# The twin scene is built at miniature 1:1 scale (same as D's SLAM map), so
# coordinates pass through untouched. Set to 10.0 only if the scene is ever
# rebuilt at full size.
SCALE = 1.0


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def quat_to_yaw(q):
    """USD quaternion (w, x, y, z) -> rotation about Z (rad).

    Ground vehicles only rotate about Z, so yaw is all we need.
    """
    w, x, y, z = q[0], q[1], q[2], q[3]
    return math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))


def yaw_to_quat(yaw):
    """yaw (rad) -> USD quaternion (w, x, y, z)."""
    half = yaw * 0.5
    return (math.cos(half), 0.0, 0.0, math.sin(half))


def now_iso():
    """The backend DTO is java.time.LocalDateTime, which rejects any timezone
    suffix. '2026-07-22T10:30:00.123' parses; adding 'Z' or '+09:00' does not.
    """
    return datetime.now().isoformat(timespec="milliseconds")


# ---------------------------------------------------------------------------
# Bridge
# ---------------------------------------------------------------------------
class TwinBridge:

    def __init__(self):
        self.sim_prim = XFormPrim(SIM_PRIM_PATH)
        self.real_prim = XFormPrim(REAL_PRIM_PATH)

        # ROS side. Commands arriving over MQTT are forwarded onto the standard
        # ROS topics so the vehicle is driven exactly the way Nav2 would drive
        # it - the backend never talks to the simulator directly.
        if not rclpy.ok():
            rclpy.init()
        self.ros = Node("twin_bridge")
        ns = SIM_ID.lower()
        self.goal_pub = self.ros.create_publisher(
            PoseStamped, f"/{ns}/goal_pose", 10)
        self.cmd_pub = self.ros.create_publisher(Twist, f"/{ns}/cmd_vel", 10)
        # cmd_vel has a 0.5s watchdog on the vehicle side, so a one-shot command
        # would stop almost immediately. Repeat the last one until it changes.
        self._manual_cmd = None

        # MQTT callbacks run on their own thread. Touching USD from there
        # crashes Isaac or corrupts state silently, so the callback only stores
        # the value and the main-thread update applies it.
        self._pending_real_pose = None
        self._lock = threading.Lock()

        self._last_pub = 0.0
        self._last_status_pub = 0.0
        self._last_xy = None
        self._elapsed = 0.0

        self.client = mqtt.Client(client_id=f"isaac-{SIM_ID}")
        self.client.on_connect = self._on_connect
        self.client.on_message = self._on_message
        # Last Will: if this process dies the broker publishes OFFLINE for us,
        # otherwise a dead vehicle sits on the HMI looking perfectly healthy.
        self.client.will_set(
            f"forklift/{SIM_ID}/status",
            json.dumps({"forkliftId": SIM_ID, "status": "OFFLINE",
                        "timestamp": None}),
            qos=1, retain=True)
        self.client.connect(BROKER_HOST, BROKER_PORT, keepalive=30)
        self.client.loop_start()   # receive thread

        self._sub = (
            omni.kit.app.get_app()
            .get_update_event_stream()
            .create_subscription_to_pop(self._on_update, name="twin_bridge")
        )
        print("[twin_bridge] started")

    # --- MQTT side (separate thread) ---------------------------------------
    def _on_connect(self, client, userdata, flags, rc):
        print(f"[twin_bridge] connected rc={rc}")
        client.subscribe(f"forklift/{REAL_ID}/location", qos=1)
        client.subscribe(f"forklift/{SIM_ID}/command", qos=1)
        client.subscribe(f"forklift/{SIM_ID}/emergency", qos=1)

    def _on_message(self, client, userdata, msg):
        try:
            data = json.loads(msg.payload.decode())
        except (ValueError, UnicodeDecodeError) as e:
            print(f"[twin_bridge] bad payload: {e}")
            return

        if msg.topic.endswith("/command"):
            self._on_command(data)
        elif msg.topic.endswith("/emergency"):
            self._on_emergency()
        else:
            with self._lock:
                self._pending_real_pose = (
                    float(data["x"]) * SCALE,
                    float(data["y"]) * SCALE,
                    float(data["direction"]),
                )

    def _on_command(self, data):
        """forklift/{id}/command -> ROS.

        Two shapes are accepted:
          {"destination": {"x":.., "y":.., "direction":..}}  -> goal_pose (Nav2)
          {"linear": 0.15, "angular": 0.3}                   -> cmd_vel (manual)

        The destination form is the real contract; the velocity form exists so
        the pipeline can be exercised before Nav2 is running.
        """
        if data.get("command") == "STOP":
            self._on_emergency()
            return

        dest = data.get("destination")
        if isinstance(dest, dict):
            with self._lock:
                self._manual_cmd = None
            goal = PoseStamped()
            goal.header.stamp = self.ros.get_clock().now().to_msg()
            goal.header.frame_id = "map"
            goal.pose.position.x = float(dest["x"]) * SCALE
            goal.pose.position.y = float(dest["y"]) * SCALE
            qw, qx, qy, qz = yaw_to_quat(float(dest.get("direction", 0.0)))
            goal.pose.orientation.x = qx
            goal.pose.orientation.y = qy
            goal.pose.orientation.z = qz
            goal.pose.orientation.w = qw
            self.goal_pub.publish(goal)
            print(f"[twin_bridge] goal -> {dest}")
            return

        if "linear" in data or "angular" in data:
            with self._lock:
                self._manual_cmd = (float(data.get("linear", 0.0)),
                                    float(data.get("angular", 0.0)))

    def _on_emergency(self):
        with self._lock:
            self._manual_cmd = (0.0, 0.0)
        self.cmd_pub.publish(Twist())
        print("[twin_bridge] emergency stop")

    # --- Isaac side (main thread) ------------------------------------------
    def _on_update(self, event):
        dt = event.payload["dt"]
        self._elapsed += dt

        rclpy.spin_once(self.ros, timeout_sec=0.0)
        self._apply_real_pose()
        self._republish_manual_cmd()

        if self._elapsed - self._last_pub >= 1.0 / PUBLISH_HZ:
            self._publish_location(self._elapsed - self._last_pub)
            self._last_pub = self._elapsed

        if self._elapsed - self._last_status_pub >= 1.0 / STATUS_HZ:
            self._publish_status()
            self._last_status_pub = self._elapsed

    def _republish_manual_cmd(self):
        """Keep feeding cmd_vel so the vehicle watchdog does not cut in."""
        with self._lock:
            cmd = self._manual_cmd
        if cmd is None:
            return
        t = Twist()
        t.linear.x, t.angular.z = cmd
        self.cmd_pub.publish(t)

    def _apply_real_pose(self):
        """Copy the real forklift pose onto the stand-in prim.

        The twin never computes anything of its own. If it ran its own motion
        model it would drift from the real vehicle within seconds and stop
        being a twin.
        """
        with self._lock:
            pose = self._pending_real_pose
            self._pending_real_pose = None
        if pose is None:
            return

        x, y, yaw = pose
        _, _, z = self.real_prim.get_world_pose()[0]   # keep authored height
        self.real_prim.set_world_pose(
            position=(x, y, z),
            orientation=yaw_to_quat(yaw),
        )

    def _publish_location(self, dt):
        pos, quat = self.sim_prim.get_world_pose()
        x, y = float(pos[0]) / SCALE, float(pos[1]) / SCALE

        # Speed from position delta. Once Nav2 is wired up, reading odom
        # directly would be more accurate.
        speed = 0.0
        if self._last_xy is not None and dt > 0:
            dx, dy = x - self._last_xy[0], y - self._last_xy[1]
            speed = math.hypot(dx, dy) / dt
        self._last_xy = (x, y)

        self._publish(f"forklift/{SIM_ID}/location", {
            "forkliftId": SIM_ID,
            "x": round(x, 4),
            "y": round(y, 4),
            "direction": round(quat_to_yaw(quat), 4),
            "speed": round(speed, 4),
            "timestamp": now_iso(),
        }, qos=0)

    def _publish_status(self):
        # TODO: report the real state. Vocabulary to agree with E:
        #       IDLE / MOVING / LIFTING / LOADING / ERROR / ESTOP / OFFLINE
        # TODO: add forkHeight and hasCargo once E extends the DTO.
        self._publish(f"forklift/{SIM_ID}/status", {
            "forkliftId": SIM_ID,
            "status": "IDLE",
            "battery": 100,
            "timestamp": now_iso(),
        }, qos=1, retain=True)

    def _publish(self, topic, payload, qos=1, retain=False):
        self.client.publish(topic, json.dumps(payload), qos=qos, retain=retain)

    def stop(self):
        self._sub.unsubscribe()
        self.client.loop_stop()
        self.client.disconnect()
        self.ros.destroy_node()
        print("[twin_bridge] stopped")


bridge = TwinBridge()
# Stop with: bridge.stop()
