#!/usr/bin/env python3
"""두 차량의 현재 위치가 실제로 충돌 상태인지 맵으로 판정한다.

"detected collision ahead" 가 계속 뜰 때, 원인이
  (a) 차가 벽/랙에 박혀 있다        -> 맵 충돌로 나온다
  (b) 두 차가 너무 붙어 있다        -> 차간 거리로 나온다
  (c) costmap 에 유령 마킹이 남았다 -> 위 둘 다 정상인데 컨트롤러만 거부
중 무엇인지 가른다.

사용:
    source /opt/ros/humble/setup.bash
    python3 where.py
"""
import math
import numpy as np
import rclpy
from rclpy.node import Node
from tf2_ros import Buffer, TransformListener
from PIL import Image

MAP_PGM = "/home/ubuntu/forklift_ws/nav2/maps/sim_warehouse.pgm"
RES = 0.05
HALF_LEN, HALF_WID = 1.9, 0.95
VEHICLES = ["SIM_F02", "SIM_F03"]

_img = np.array(Image.open(MAP_PGM))
_H, _W = _img.shape
_free = _img > 250


def map_clear(cx, cy, yaw, margin=0.0):
    """(cx, cy, yaw) 에 놓인 차체 사각형이 맵상 자유공간인지."""
    c, s = math.cos(yaw), math.sin(yaw)
    step = 0.2
    nx = int((HALF_LEN + margin) / step)
    ny = int((HALF_WID + margin) / step)
    for i in range(-nx, nx + 1):
        for j in range(-ny, ny + 1):
            lx, ly = i * step, j * step
            wx = cx + lx * c - ly * s
            wy = cy + lx * s + ly * c
            px = int(wx / RES)
            py = int(_H - wy / RES)
            if px < 0 or py < 0 or px >= _W or py >= _H:
                return False
            if not _free[py, px]:
                return False
    return True


def main():
    rclpy.init()
    node = Node("where")
    from rclpy.parameter import Parameter
    node.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
    buf = Buffer()
    TransformListener(buf, node)

    import time
    poses = {}
    t0 = time.time()
    while time.time() - t0 < 8.0 and len(poses) < len(VEHICLES):
        rclpy.spin_once(node, timeout_sec=0.2)
        for f in VEHICLES:
            if f in poses:
                continue
            try:
                t = buf.lookup_transform("map", f"{f}_base_link",
                                         rclpy.time.Time())
            except Exception:
                continue
            q = t.transform.rotation
            yaw = math.atan2(2 * (q.w * q.z + q.x * q.y),
                             1 - 2 * (q.y * q.y + q.z * q.z))
            poses[f] = (t.transform.translation.x,
                        t.transform.translation.y, yaw)

    if not poses:
        print("TF 를 못 받음 — clock_pub / Nav2 확인")
        rclpy.shutdown()
        return

    for f in VEHICLES:
        if f not in poses:
            print(f"{f}: TF 없음")
            continue
        x, y, yaw = poses[f]
        ok0 = map_clear(x, y, yaw)
        ok4 = map_clear(x, y, yaw, margin=0.4)
        print(f"{f}: ({x:.2f}, {y:.2f}) 방향 {math.degrees(yaw):>6.1f}도")
        print(f"    맵 충돌 여부: 차체만 {'자유' if ok0 else '★박힘★'} / "
              f"여유0.4m {'자유' if ok4 else '빡빡'}")

    if len(poses) == 2:
        a, b = poses[VEHICLES[0]], poses[VEHICLES[1]]
        d = math.hypot(a[0] - b[0], a[1] - b[1])
        need = 2 * HALF_LEN
        print(f"\n두 차량 거리: {d:.2f} m "
              f"({'겹칠 수 있음 - 너무 가까움' if d < need else '여유 있음'}, "
              f"최소 {need:.1f} m 권장)")

    rclpy.shutdown()


if __name__ == "__main__":
    main()
