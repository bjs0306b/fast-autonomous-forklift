"""유령 노드 확인 — "아무 명령 안 줬는데 움직인다" 를 진단한다.

같은 프림을 여러 노드가 동시에 쓰면 차량이 제멋대로 움직인다.
원인은 대개 셋 중 하나다.

    1. Isaac 에서 kinematic_vehicle.py 를 여러 번 exec 해 fleet 이 겹침
    2. 이전 Nav2 가 안 죽어서 cmd_vel 을 계속 발행
    3. 이전 demo_loop2.py 가 다른 터미널에서 아직 돌고 있음

사용:
    source /opt/ros/humble/setup.bash
    python3 check_ghosts.py
"""
import subprocess
import sys

import rclpy
from rclpy.node import Node

NS = ["sim_f02", "sim_f03"]


def sh(cmd):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True,
                              text=True, timeout=15).stdout.strip()
    except Exception:
        return ""


def main():
    rclpy.init()
    n = Node("check_ghosts")
    rclpy.spin_once(n, timeout_sec=2.0)

    bad = []
    print("=== 유령 노드 확인 ===\n")

    # 1. 차량 노드 — 하나만 있어야 한다
    print("[1] 차량 노드 (kinematic_fleet)")
    names = [x for x in n.get_node_names() if "kinematic" in x]
    print(f"  {len(names)}개: {names}")
    if len(names) > 1:
        print("  ✗ 여러 개! Isaac 에서 kinematic_vehicle.py 가 중복 실행됐습니다.")
        print("    -> Script Editor:  fleet.stop()")
        print("       그 뒤 isaac_setup.py 를 다시 실행하세요.")
        bad.append("차량 노드 중복")
    elif not names:
        print("  ✗ 없음 — Isaac 에서 kinematic_vehicle.py 를 실행하세요")
        bad.append("차량 노드 없음")
    else:
        print("  ✓ 하나")

    # 2. 발행자 수 — 같은 토픽을 여럿이 쓰면 서로 싸운다
    print("\n[2] 토픽 발행자 수 (여럿이면 서로 덮어씁니다)")
    for ns in NS:
        for topic, want in ((f"/{ns}/cmd_vel", 1), (f"/{ns}/odom", 1),
                            (f"/{ns}/state", 1)):
            k = n.count_publishers(topic)
            mark = "✓" if k <= want else "✗"
            print(f"  {mark} {topic:22s} {k}개")
            if k > want:
                bad.append(f"{topic} 발행자 {k}개")

    # 3. /clock — 하나만
    k = n.count_publishers("/clock")
    print(f"\n[3] /clock 발행자 {k}개  "
          f"{'✓' if k == 1 else '✗ (0이면 Nav2 가 얼어붙습니다)'}")
    if k != 1:
        bad.append(f"/clock 발행자 {k}개")

    # 4. 남아 있는 프로세스
    print("\n[4] 살아 있는 프로세스")
    for pat, label in (("bt_navigator", "Nav2 bt_navigator"),
                       ("controller_server", "Nav2 controller"),
                       ("demo_loop2", "데모"),
                       ("fleet_obstacles", "장애물 발행")):
        out = sh(f"pgrep -f {pat} | wc -l")
        cnt = int(out or 0)
        want = 1 if pat.startswith(("bt_", "controller", "fleet")) else 0
        mark = "✓" if cnt <= max(want, 1) else "✗"
        print(f"  {mark} {label:20s} {cnt}개")
        if cnt > 1:
            bad.append(f"{label} {cnt}개")

    print("\n" + "=" * 50)
    if bad:
        print("문제:")
        for b in bad:
            print(f"  - {b}")
        print("\n정리 방법:")
        print("  Isaac Script Editor:")
        print("    fleet.stop()")
        print("    exec(open('.../isaac_setup.py').read())   # Stop 상태에서")
        print("  터미널:")
        print("    pkill -9 -f 'bt_navigator|controller_server|demo_loop2'")
        print("    ./nav2/scripts/run_all.sh --no-mqtt")
    else:
        print("✓ 유령 없음. 명령 없이 움직인다면 스텝 머신이 도는 중입니다:")
        print("    ros2 topic echo /sim_f02/state --once   # mission / step 확인")

    n.destroy_node()
    rclpy.shutdown()
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
