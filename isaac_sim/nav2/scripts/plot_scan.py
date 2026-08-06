#!/usr/bin/env python3
"""F02 라이다가 찍은 점을 맵 위에 그려 PNG 로 저장한다.

맵(벽/랙) 위에 라이다 히트 점(빨강)과 F02 위치(파랑)를 얹어서,
라이다가 실제로 무엇을 재고 있는지 한눈에 보여준다. RViz 없이 그림 파일로.

사용:
    source /opt/ros/humble/setup.bash
    python3 plot_scan.py
    -> /home/ubuntu/forklift_ws/nav2/scan_view.png 생성
"""
import math
import numpy as np
import rclpy
from rclpy.node import Node
from sensor_msgs.msg import LaserScan
from tf2_ros import Buffer, TransformListener

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

OUT = "/home/ubuntu/forklift_ws/nav2/scan_view.png"
MAP_PGM = "/home/ubuntu/forklift_ws/nav2/maps/sim_warehouse.pgm"
RES = 0.05


def main():
    rclpy.init()
    node = Node("plot_scan")
    from rclpy.parameter import Parameter
    node.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
    buf = Buffer()
    TransformListener(buf, node)
    scan = {"msg": None}
    node.create_subscription(LaserScan, "/sim_f02/scan",
                             lambda m: scan.__setitem__("msg", m), 10)

    import time
    # scan 받기
    t0 = time.time()
    while rclpy.ok() and time.time() - t0 < 8.0:
        rclpy.spin_once(node, timeout_sec=0.1)
        if scan["msg"] is not None and time.time() - t0 > 1.0:
            break
    m = scan["msg"]
    if m is None:
        print("scan 못 받음")
        return

    # 라이다 프레임의 map 상 위치/방향 (TF 누적될 때까지 재시도)
    tf = None
    t1 = time.time()
    while rclpy.ok() and time.time() - t1 < 8.0:
        rclpy.spin_once(node, timeout_sec=0.1)
        try:
            tf = buf.lookup_transform("map", m.header.frame_id, rclpy.time.Time())
            break
        except Exception:
            continue
    if tf is None:
        print("TF(map->라이다) 못 얻음. Nav2/static_tf 켜져있나 확인")
        return
    lx = tf.transform.translation.x
    ly = tf.transform.translation.y
    q = tf.transform.rotation
    lyaw = math.atan2(2 * (q.w * q.z + q.x * q.y),
                      1 - 2 * (q.y * q.y + q.z * q.z))

    # 라이다 히트 -> 월드 좌표
    xs, ys = [], []
    a = m.angle_min
    for r in m.ranges:
        if 0.05 < r < 25 and not math.isinf(r):
            wx = lx + r * math.cos(lyaw + a)
            wy = ly + r * math.sin(lyaw + a)
            xs.append(wx); ys.append(wy)
        a += m.angle_increment

    # F03 위치도 표시 (TF 있으면)
    f3 = None
    try:
        t3 = buf.lookup_transform("map", "SIM_F03_base_link", rclpy.time.Time())
        f3 = (t3.transform.translation.x, t3.transform.translation.y)
    except Exception:
        pass

    # 맵 배경
    fig, ax = plt.subplots(figsize=(8, 12))
    try:
        from PIL import Image
        img = np.array(Image.open(MAP_PGM))
        h, w = img.shape
        ax.imshow(img, cmap="gray", extent=[0, w * RES, 0, h * RES],
                  origin="upper", alpha=0.6)
    except Exception as e:
        print("맵 배경 로드 실패(무시):", e)

    ax.scatter(xs, ys, s=4, c="red", label=f"lidar hits ({len(xs)})")
    ax.scatter([lx], [ly], s=120, c="blue", marker="s", label="F02 (lidar)")
    ax.arrow(lx, ly, 1.2 * math.cos(lyaw), 1.2 * math.sin(lyaw),
             head_width=0.4, color="blue")
    if f3:
        ax.scatter([f3[0]], [f3[1]], s=120, c="green", marker="s", label="F03")
    ax.set_xlabel("x (m)"); ax.set_ylabel("y (m)")
    ax.set_title("F02 lidar hits on map")
    ax.legend(loc="upper right"); ax.set_aspect("equal"); ax.grid(alpha=0.3)
    fig.savefig(OUT, dpi=90, bbox_inches="tight")
    print(f"저장: {OUT}")
    print(f"라이다 히트 {len(xs)}개, F02({lx:.1f},{ly:.1f})"
          + (f", F03{f3}" if f3 else ""))
    if rclpy.ok():
        rclpy.shutdown()


if __name__ == "__main__":
    main()
