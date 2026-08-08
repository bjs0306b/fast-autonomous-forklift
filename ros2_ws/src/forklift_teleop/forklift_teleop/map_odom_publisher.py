"""Publish map->odom, and let someone correct it when the guess is wrong.

Running on the simulator's map instead of building one gives a single
coordinate system, but it costs an assumption: that the vehicle was placed
exactly where the launch says it was. A static transform bakes that guess in
permanently, and every coordinate is offset by however wrong it was -- with no
way to tell, because the map still looks self-consistent.

This publishes the same transform and accepts a correction on /initialpose,
which is what RViz's "2D Pose Estimate" sends. Point at where the vehicle
really is and the whole frame snaps to it.

⚠️ **This is not AMCL and does not track.** Nothing runs scan matching, so
   nothing can drift onto the wrong aisle -- the failure the simulator's own
   notes warn about in a repetitive warehouse, and this mockup has only two
   rack rows to confuse it with. The cost is that odometry drift is never
   corrected either: the transform holds exactly what it was last told.

⚠️ **Correcting mid-mission moves the world, not the vehicle.** Everything
   already planned in map coordinates -- goals, the global path, costmap
   marks -- refers to the old frame. Correct while stopped, then re-plan.
"""

import math

from geometry_msgs.msg import PoseWithCovarianceStamped, TransformStamped
import rclpy
from rclpy.node import Node
from tf2_ros import Buffer, TransformBroadcaster, TransformListener


def _yaw(rotation) -> float:
    return math.atan2(2.0 * rotation.w * rotation.z,
                      1.0 - 2.0 * rotation.z * rotation.z)


class MapOdomPublisher(Node):
    def __init__(self) -> None:
        super().__init__("map_odom_publisher")

        self.declare_parameter("start_x_m", 0.30)
        self.declare_parameter("start_y_m", 0.20)
        self.declare_parameter("start_yaw_rad", 0.0)
        self.declare_parameter("map_frame", "map")
        self.declare_parameter("odom_frame", "odom")
        self.declare_parameter("base_frame", "base_link")
        self.declare_parameter("publish_rate_hz", 20.0)

        self._map = str(self.get_parameter("map_frame").value)
        self._odom = str(self.get_parameter("odom_frame").value)
        self._base = str(self.get_parameter("base_frame").value)

        # Held as map->odom directly. At startup odom->base_link is identity,
        # so the launch guess is also the transform.
        self._x = float(self.get_parameter("start_x_m").value)
        self._y = float(self.get_parameter("start_y_m").value)
        self._yaw = float(self.get_parameter("start_yaw_rad").value)

        self._buffer = Buffer()
        TransformListener(self._buffer, self)
        self._broadcaster = TransformBroadcaster(self)
        self.create_subscription(
            PoseWithCovarianceStamped, "/initialpose", self._on_initial, 10)

        rate = float(self.get_parameter("publish_rate_hz").value)
        if rate <= 0.0:
            raise ValueError("publish_rate_hz must be positive")
        self.create_timer(1.0 / rate, self._publish)

        self.get_logger().info(
            f"map->odom at x={self._x:.3f} y={self._y:.3f} "
            f"yaw={math.degrees(self._yaw):.1f}deg. "
            f"Correct it with RViz 2D Pose Estimate."
        )

    def _on_initial(self, message: PoseWithCovarianceStamped) -> None:
        """Place base_link at the given map pose by moving map->odom."""
        if message.header.frame_id not in ("", self._map):
            self.get_logger().warn(
                f"initialpose is in '{message.header.frame_id}', expected "
                f"'{self._map}' -- ignoring"
            )
            return
        try:
            odom_base = self._buffer.lookup_transform(
                self._odom, self._base, rclpy.time.Time())
        except Exception as error:
            self.get_logger().warn(f"odom->base_link unavailable: {error}")
            return

        # map->odom = wanted(map->base) composed with (odom->base) inverted.
        ox = odom_base.transform.translation.x
        oy = odom_base.transform.translation.y
        oyaw = _yaw(odom_base.transform.rotation)

        wx = message.pose.pose.position.x
        wy = message.pose.pose.position.y
        wyaw = _yaw(message.pose.pose.orientation)

        self._yaw = wyaw - oyaw
        cos_a, sin_a = math.cos(self._yaw), math.sin(self._yaw)
        self._x = wx - (ox * cos_a - oy * sin_a)
        self._y = wy - (ox * sin_a + oy * cos_a)

        self.get_logger().info(
            f"map->odom corrected to x={self._x:.3f} y={self._y:.3f} "
            f"yaw={math.degrees(self._yaw):.1f}deg "
            f"(vehicle placed at {wx:.3f},{wy:.3f})"
        )

    def _publish(self) -> None:
        transform = TransformStamped()
        transform.header.stamp = self.get_clock().now().to_msg()
        transform.header.frame_id = self._map
        transform.child_frame_id = self._odom
        transform.transform.translation.x = self._x
        transform.transform.translation.y = self._y
        transform.transform.rotation.z = math.sin(self._yaw / 2.0)
        transform.transform.rotation.w = math.cos(self._yaw / 2.0)
        self._broadcaster.sendTransform(transform)


def main(args=None) -> None:
    rclpy.init(args=args)
    node = MapOdomPublisher()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
