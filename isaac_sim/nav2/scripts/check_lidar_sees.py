#!/usr/bin/env python3
"""F02 라이다가 '정확히 F03 방향' 으로 무엇을 재는지 확인한다.

scan 의 전체 최소값은 뒤 벽일 수도 있어 무의미하다. 이 스크립트는
F02 라이다에서 F03(6,4) 을 향하는 광선의 실제 측정값을 뽑아, 기대거리
(F02~F03 직선거리)와 비교한다. 값이 기대거리와 비슷하면 라이다가 F03 을
제대로 보는 것이고, 훨씬 크면 F03 을 못 보고 뒤 벽 등을 재는 것이다.

사용:
    source /opt/ros/humble/setup.bash
    python3 check_lidar_sees.py            # F03 = (6,4) 기본
    python3 check_lidar_sees.py 6 4        # 대상 좌표 지정
"""
import sys
import math
import rclpy
from rclpy.node import Node
from sensor_msgs.msg import LaserScan
from tf2_ros import Buffer, TransformListener


class LidarSees(Node):
    def __init__(self, tx, ty):
        super().__init__("lidar_sees")
        from rclpy.parameter import Parameter
        self.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
        self.tx, self.ty = tx, ty
        self.buf = Buffer()
        self.tl = TransformListener(self.buf, self)
        self.scan = None
        self.create_subscription(LaserScan, "/sim_f02/scan", self.cb, 10)
        self.done = False

    def cb(self, msg):
        self.scan = msg

    def analyze(self):
        if self.scan is None:
            print("scan 못 받음")
            return True
        # 라이다 프레임의 map 상 위치/방향
        try:
            tf = self.buf.lookup_transform(
                "map", self.scan.header.frame_id, rclpy.time.Time())
        except Exception as e:
            print("TF 못 얻음:", e)
            return False
        lx = tf.transform.translation.x
        ly = tf.transform.translation.y
        q = tf.transform.rotation
        lyaw = math.atan2(2 * (q.w * q.z + q.x * q.y),
                          1 - 2 * (q.y * q.y + q.z * q.z))

        dx, dy = self.tx - lx, self.ty - ly
        expected = math.hypot(dx, dy)
        bearing_world = math.atan2(dy, dx)
        bearing_laser = bearing_world - lyaw
        # -pi~pi 정규화
        bearing_laser = (bearing_laser + math.pi) % (2 * math.pi) - math.pi

        s = self.scan
        idx = int(round((bearing_laser - s.angle_min) / s.angle_increment))
        n = len(s.ranges)

        print(f"F02 라이다 위치: ({lx:.2f}, {ly:.2f}), 방향 {math.degrees(lyaw):.0f}도")
        print(f"F03({self.tx},{self.ty}) 까지 기대거리: {expected:.2f} m, "
              f"F03 방향(라이다기준): {math.degrees(bearing_laser):.0f}도")
        print(f"scan: {n}개, 범위 {math.degrees(s.angle_min):.0f}~"
              f"{math.degrees(s.angle_max):.0f}도")

        # F03 방향 ±15도 창에서 유효 최소값
        win = int(math.radians(15) / abs(s.angle_increment))
        vals = []
        for k in range(idx - win, idx + win + 1):
            if 0 <= k < n:
                r = s.ranges[k]
                if 0.05 < r < 100 and not math.isinf(r):
                    vals.append(r)
        print("\n=== F03 방향 ±15도에서 라이다 측정 ===")
        if vals:
            print(f"측정 최소: {min(vals):.2f} m  (기대 {expected:.2f} m)")
            diff = min(vals) - expected
            if abs(diff) < 1.0:
                print(">>> 라이다가 F03 을 제대로 봄! (거의 일치)")
            elif min(vals) > expected + 1.0:
                print(">>> F03 방향인데 더 먼 값 = F03 을 못 보고 너머(벽)를 잼!")
            else:
                print(">>> 기대보다 가까움 - 다른 물체 or F03 앞부분")
        else:
            print(">>> F03 방향에 유효 측정 없음(-1/inf) = 그 방향을 아예 못 봄!")
        return True


def main():
    tx = float(sys.argv[1]) if len(sys.argv) > 1 else 6.0
    ty = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
    rclpy.init()
    node = LidarSees(tx, ty)
    import time
    t0 = time.time()
    while rclpy.ok() and time.time() - t0 < 8.0:
        rclpy.spin_once(node, timeout_sec=0.2)
        if node.scan is not None and time.time() - t0 > 1.5:
            if node.analyze():
                break
    if node.scan is None:
        print(">>> 8초간 scan 못 받음")
    if rclpy.ok():
        rclpy.shutdown()


if __name__ == "__main__":
    main()
