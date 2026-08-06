"""적재 경로만 따로 시험한다 — 데모를 돌리기 전에 이걸로 먼저 확인.

차량을 바이로 옮기지는 않는다. 지금 있는 자리에서 화물 명령을 보내고,
/state 로 실제로 실렸는지 확인한 뒤 어디서 끊겼는지 알려준다.

사용:
    source /opt/ros/humble/setup.bash
    python3 test_load.py                # SIM_F02
    python3 test_load.py sim_f03        # 다른 차량
    python3 test_load.py sim_f02 0.15   # 높이 지정 (실물 m)
"""
import sys
import json
import time

import rclpy
from rclpy.node import Node
from std_msgs.msg import String


class LoadTest(Node):
    def __init__(self, ns, height):
        super().__init__("test_load")
        self.ns = ns
        self.height = height
        self.state = None
        self.n_state = 0
        self.create_subscription(String, f"/{ns}/state", self.on_state, 10)
        self.pub = self.create_publisher(String, f"/{ns}/cargo_cmd", 10)

    def on_state(self, msg):
        try:
            self.state = json.loads(msg.data)
            self.n_state += 1
        except Exception:
            pass

    def wait(self, sec):
        end = time.time() + sec
        while time.time() < end and rclpy.ok():
            rclpy.spin_once(self, timeout_sec=0.1)


def main():
    ns = sys.argv[1] if len(sys.argv) > 1 else "sim_f02"
    height = float(sys.argv[2]) if len(sys.argv) > 2 else 0.15

    rclpy.init()
    t = LoadTest(ns, height)
    print(f"=== /{ns} 적재 경로 시험 ===\n")

    # 1. 차량 노드가 살아 있는가
    print("[1/5] /state 수신 확인 (5초)")
    t.wait(5.0)
    if t.state is None:
        print(f"  ✗ /{ns}/state 가 안 옵니다.")
        print("    -> Isaac 이 Play 중인지, Script Editor 에서")
        print("       kinematic_vehicle.py 가 실행됐는지 확인하세요.")
        print(f"    -> ros2 topic list | grep {ns}")
        return _end(t, False)
    print(f"  ✓ {t.n_state}건 수신")
    p = t.state.get("pose", {})
    print(f"    위치 ({p.get('x')}, {p.get('y')})  방향 {p.get('yaw')} rad")
    print(f"    loaded={t.state.get('loaded')}  mission={t.state.get('mission')}"
          f"  step={t.state.get('step')!r}")
    if "mission" not in t.state:
        print("    ! mission 필드 없음 — kinematic_vehicle.py 가 옛 버전입니다.")
        print("      Script Editor 에서 다시 exec 하세요.")

    # 2. 구독자가 있는가 (cargo_demo 가 로드됐는가)
    print("\n[2/5] cargo_cmd 구독자 확인")
    n = t.pub.get_subscription_count()
    print(f"  구독자 {n}개")
    if n == 0:
        print(f"  ✗ /{ns}/cargo_cmd 를 아무도 안 듣습니다.")
        print("    -> cargo_demo.py 로드가 실패했을 가능성이 큽니다.")
        print("       Script Editor 에서:")
        print("       exec(open('.../cargo_demo.py').read())")
        print("       enable_mqtt_cargo()")
        print("       (에러가 뜨면 그 메시지가 원인입니다)")
        return _end(t, False)
    print("  ✓ 듣고 있음")

    # 3. 들고 있던 화물 정리
    if t.state.get("loaded"):
        print("\n  이미 화물을 들고 있음 — 그대로 시험합니다"
              " (자동으로 떼어내고 새로 받습니다)")

    # 4. 화물 요청
    print(f"\n[3/5] 화물 요청 (높이 {height} m)")
    t.pub.publish(String(data=json.dumps({"height": height})))
    t.wait(1.0)

    # 5. 실렸는지 확인
    print("[4/5] 적재 확인 (최대 20초)")
    end = time.time() + 20.0
    while time.time() < end and rclpy.ok():
        rclpy.spin_once(t, timeout_sec=0.2)
        if t.state and t.state.get("loaded"):
            break
    ok = bool(t.state and t.state.get("loaded"))

    print("\n[5/5] 결과")
    if ok:
        print(f"  ✓ 적재 성공 — cargoId {t.state.get('cargoId')}")
        print(f"    포크 높이 {t.state.get('forkHeight')}")
        print("\n  적재 경로는 정상입니다. demo_loop2.py 를 돌려도 됩니다.")
    else:
        print("  ✗ 20초 안에 실리지 않았습니다.")
        print(f"    마지막 상태: loaded={t.state.get('loaded')}"
              f" mission={t.state.get('mission')} step={t.state.get('step')!r}")
        print("\n    Isaac 콘솔에서 이 줄들을 찾아보세요:")
        print("      [cargo_cmd] ... <- {...}        명령이 도착했는가")
        print("      [cargo_cmd] ... 적재 결과: ...   왜 실패했는가")
        print("      ── 적재 진단 ──                  상세 사유")
        print("\n    Script Editor 에서 바로 확인:")
        print(f"      why_no_cargo('{ns.upper()}')")
    return _end(t, ok)


def _end(t, ok):
    t.destroy_node()
    rclpy.shutdown()
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
