#!/usr/bin/env python3
"""F02 가 실제로 지나간 궤적을 기록해 '휘었나 직진했나' 를 숫자로 판정한다.

목표를 준 뒤 이 스크립트를 켜두면, F02 의 map 상 위치(TF)를 계속 모아
y 가 얼마나 변했는지(휨 정도)를 출력한다. 경로(plan)는 휘는데 실제 궤적
y 가 거의 안 변하면 -> 컨트롤러가 경로를 안 따라간 것.

사용:
    source /opt/ros/humble/setup.bash
    python3 record_path.py 12      # 12초간 기록 (기본 12)
"""
import sys
import math
import rclpy
from rclpy.node import Node
from tf2_ros import Buffer, TransformListener


def main():
    dur = float(sys.argv[1]) if len(sys.argv) > 1 else 12.0
    rclpy.init()
    node = Node("record_path")
    from rclpy.parameter import Parameter
    node.set_parameters([Parameter("use_sim_time", Parameter.Type.BOOL, True)])
    buf = Buffer()
    TransformListener(buf, node)

    import time
    pts = []
    t0 = time.time()
    print(f"{dur:.0f}초간 F02 궤적 기록 중... (목표를 지금 주거나 이미 준 상태)")
    while rclpy.ok() and time.time() - t0 < dur:
        rclpy.spin_once(node, timeout_sec=0.1)
        try:
            tf = buf.lookup_transform("map", "SIM_F02_base_link", rclpy.time.Time())
            x = tf.transform.translation.x
            y = tf.transform.translation.y
            if not pts or math.hypot(x - pts[-1][0], y - pts[-1][1]) > 0.05:
                pts.append((x, y))
        except Exception:
            pass

    if len(pts) < 2:
        print("궤적 거의 없음 - F02 가 안 움직였다")
    else:
        xs = [p[0] for p in pts]
        ys = [p[1] for p in pts]
        print(f"\n기록점 {len(pts)}개")
        print(f"시작 ({xs[0]:.1f},{ys[0]:.1f}) -> 끝 ({xs[-1]:.1f},{ys[-1]:.1f})")
        print(f"이동 거리(x): {abs(xs[-1]-xs[0]):.1f} m")
        print(f"실제 궤적 y범위: {min(ys):.2f} ~ {max(ys):.2f}  (휨폭 {max(ys)-min(ys):.2f} m)")
        print()
        if max(ys) - min(ys) < 0.5:
            print(">>> 실제 궤적이 거의 직선! 경로가 휘었다면 컨트롤러가 안 따라간 것")
        else:
            print(">>> 실제 궤적이 휨 = F02 가 실제로 피하려 함")
    if rclpy.ok():
        rclpy.shutdown()


if __name__ == "__main__":
    main()
