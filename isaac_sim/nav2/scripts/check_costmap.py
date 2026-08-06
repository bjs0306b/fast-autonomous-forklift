#!/usr/bin/env python3
"""F02 의 global costmap 에서 특정 좌표 주변의 최대 cost 를 찍어본다.

라이다가 F03 을 2.89m 로 보는데도 회피를 안 하면, costmap 이 F03 을
장애물로 마킹하는지가 관건이다. 이 스크립트는 (tx, ty) 주변 반경 안의
셀 cost 를 훑어서, 0(자유) 이면 마킹 안 됨, 90~100(치명) 이면 마킹됨을
알려준다.

사용:
    source /opt/ros/humble/setup.bash
    python3 check_costmap.py 6.0 4.0        # F03 위치 (6,4) 주변 확인
"""
import sys
import rclpy
from rclpy.node import Node
from rclpy.qos import QoSProfile, QoSDurabilityPolicy, QoSReliabilityPolicy
from nav_msgs.msg import OccupancyGrid


class Checker(Node):
    def __init__(self, tx, ty):
        super().__init__("costmap_checker")
        self.tx, self.ty = tx, ty
        self.got = False
        qos = QoSProfile(
            depth=1,
            durability=QoSDurabilityPolicy.TRANSIENT_LOCAL,
            reliability=QoSReliabilityPolicy.RELIABLE,
        )
        self.create_subscription(
            OccupancyGrid, "/sim_f02/global_costmap/costmap", self.cb, qos)
        self.get_logger().info("costmap 대기중...")

    def cb(self, msg):
        if self.got:
            return
        self.got = True
        w, h = msg.info.width, msg.info.height
        res = msg.info.resolution
        ox, oy = msg.info.origin.position.x, msg.info.origin.position.y
        print(f"costmap: {w}x{h} @ {res}m, origin=({ox},{oy})")

        cx = int((self.tx - ox) / res)
        cy = int((self.ty - oy) / res)
        # (tx,ty) 주변 ±0.9m(=18셀) 안에서 최대 cost 찾기
        R = 18
        best = -1
        best_xy = None
        marked = 0
        for dy in range(-R, R + 1):
            for dx in range(-R, R + 1):
                x, y = cx + dx, cy + dy
                if 0 <= x < w and 0 <= y < h:
                    v = msg.data[y * w + x]
                    if v > 0:
                        marked += 1
                    if v > best:
                        best = v
                        best_xy = (ox + x * res, oy + y * res)
        print(f"\n=== ({self.tx},{self.ty}) 주변 ±0.9m ===")
        print(f"최대 cost: {best}  (위치 {best_xy})")
        print(f"cost>0 인 셀 개수: {marked}")
        if best >= 90:
            print(">>> F03 이 장애물로 마킹됨! (회피해야 정상)")
        elif best > 0:
            print(">>> 약하게 마킹됨(inflation?). 치명(lethal) 아님")
        else:
            print(">>> 마킹 안 됨! costmap 이 F03 을 못 봄 (이게 통과 원인)")
        rclpy.shutdown()


def main():
    tx = float(sys.argv[1]) if len(sys.argv) > 1 else 6.0
    ty = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
    rclpy.init()
    node = Checker(tx, ty)
    # 10초까지 스핀. 그 안에 콜백이 못 오면 토픽을 못 받은 것.
    import time
    t0 = time.time()
    while rclpy.ok() and not node.got and time.time() - t0 < 10.0:
        rclpy.spin_once(node, timeout_sec=0.2)
    if not node.got:
        print(">>> 10초 안에 costmap 을 못 받음! "
              "global_costmap 이 발행을 안 하거나 QoS 문제.")
    if rclpy.ok():
        rclpy.shutdown()


if __name__ == "__main__":
    main()
