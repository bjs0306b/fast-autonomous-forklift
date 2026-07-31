#!/usr/bin/env python3
"""ForkliftB 를 띄우고 ROS2 토픽으로 조종할 수 있게 만드는 스크립트.

GUI 에서 노드를 손으로 잇는 대신 이 스크립트가 OmniGraph 를 자동으로 만든다.
실행하면 Isaac Sim 창이 뜨고, 지게차가 /joint_command 토픽을 기다린다.

실행:
    source /opt/ros/humble/setup.bash
    ~/isaacsim/python.sh scripts/run_forklift_sim.py

만들어지는 그래프 (노드 6개):

    OnPlaybackTick ──> ROS2SubscribeJointState ──> ArticulationController
                                                        (관절을 실제로 움직임)
                   ──> ROS2PublishClock          (시뮬 시간을 ROS2 로)
                   ──> ROS2PublishJointState     (현재 관절 상태를 ROS2 로)

Play 를 누른 상태에서만 토픽이 동작한다. 이 스크립트는 자동으로 Play 한다.
"""
import os
import sys

from isaacsim import SimulationApp

# 기본은 창을 띄운다. HEADLESS=1 을 주면 창 없이 돈다(원격 접속·검증용).
HEADLESS = os.environ.get("HEADLESS", "0") == "1"
# SELFTEST=1 이면 그래프만 만들어보고 바로 종료한다. 설정이 맞는지 확인용.
SELFTEST = os.environ.get("SELFTEST", "0") == "1"
simulation_app = SimulationApp({"headless": HEADLESS or SELFTEST})

# SimulationApp 생성 이후에만 아래 모듈들을 import 할 수 있다. (Isaac 의 규칙)
import omni.graph.core as og  # noqa: E402
from isaacsim.core.api import World  # noqa: E402
from isaacsim.core.utils.extensions import enable_extension  # noqa: E402
from isaacsim.core.utils.stage import add_reference_to_stage  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "forklift_control"))
from forklift_control import fast_params as P  # noqa: E402

# ROS2 브리지 확장을 켠다. 이게 없으면 ROS2 노드가 아예 생성되지 않는다.
enable_extension("isaacsim.ros2.bridge")
simulation_app.update()

FORKLIFT_USD = (
    "https://omniverse-content-production.s3-us-west-2.amazonaws.com"
    "/Assets/Isaac/5.1/Isaac/Robots/IsaacSim/ForkliftB/forklift_b_sensor.usd"
)


def main():
    world = World(stage_units_in_meters=1.0)
    world.scene.add_default_ground_plane()

    # 지게차를 스테이지에 올린다.
    add_reference_to_stage(usd_path=FORKLIFT_USD, prim_path=P.PRIM_ROOT)

    # --- 그래프 구성 ------------------------------------------------------
    og.Controller.edit(
        {"graph_path": "/ActionGraph", "evaluator_name": "execution"},
        {
            og.Controller.Keys.CREATE_NODES: [
                ("OnTick", "omni.graph.action.OnPlaybackTick"),
                ("Context", "isaacsim.ros2.bridge.ROS2Context"),
                ("SimTime", "isaacsim.core.nodes.IsaacReadSimulationTime"),
                ("SubJoint", "isaacsim.ros2.bridge.ROS2SubscribeJointState"),
                ("ArtCtrl", "isaacsim.core.nodes.IsaacArticulationController"),
                ("PubClock", "isaacsim.ros2.bridge.ROS2PublishClock"),
                ("PubJoint", "isaacsim.ros2.bridge.ROS2PublishJointState"),
            ],
            og.Controller.Keys.CONNECT: [
                # 매 프레임마다 세 노드를 실행시킨다.
                ("OnTick.outputs:tick", "SubJoint.inputs:execIn"),
                ("OnTick.outputs:tick", "PubClock.inputs:execIn"),
                ("OnTick.outputs:tick", "PubJoint.inputs:execIn"),
                # ROS2 접속 정보(도메인 등)를 각 노드에 물린다.
                ("Context.outputs:context", "SubJoint.inputs:context"),
                ("Context.outputs:context", "PubClock.inputs:context"),
                ("Context.outputs:context", "PubJoint.inputs:context"),
                # 시뮬 시간을 타임스탬프로.
                ("SimTime.outputs:simulationTime", "PubClock.inputs:timeStamp"),
                ("SimTime.outputs:simulationTime", "PubJoint.inputs:timeStamp"),
                # 구독한 관절 지령을 그대로 관절 제어기로 넘긴다.
                # 출력 이름과 입력 이름이 1:1 로 맞아떨어진다.
                ("SubJoint.outputs:execOut", "ArtCtrl.inputs:execIn"),
                ("SubJoint.outputs:jointNames", "ArtCtrl.inputs:jointNames"),
                ("SubJoint.outputs:positionCommand", "ArtCtrl.inputs:positionCommand"),
                ("SubJoint.outputs:velocityCommand", "ArtCtrl.inputs:velocityCommand"),
            ],
            og.Controller.Keys.SET_VALUES: [
                ("SubJoint.inputs:topicName", P.TOPIC_JOINT_COMMAND),
                ("PubJoint.inputs:topicName", P.TOPIC_JOINT_STATES),
                # 어느 로봇을 제어할지 알려준다.
                ("ArtCtrl.inputs:robotPath", P.PRIM_ROOT),
                ("PubJoint.inputs:targetPrim", [P.PRIM_ROOT]),
            ],
        },
    )

    world.reset()

    print("\n" + "=" * 60)
    print("  준비 완료. 다른 터미널에서 확인해보세요:")
    print("    ros2 topic list")
    print("    ros2 topic echo /joint_states")
    print("  움직이려면:")
    print("    ros2 run forklift_control cmd_vel_to_joints")
    print("    ros2 topic pub /cmd_vel geometry_msgs/msg/Twist \\")
    print('      "{linear: {x: 1.0}, angular: {z: 0.3}}"')
    print("  종료: 이 창에서 Ctrl+C")
    print("=" * 60 + "\n")

    if SELFTEST:
        # 그래프가 제대로 만들어졌는지만 확인하고 끝낸다.
        # Isaac 이 stdout 을 가로채므로 결과는 파일로 남긴다.
        for _ in range(10):
            world.step(render=False)
        lines = []
        try:
            graph = og.get_graph_by_path("/ActionGraph")
            for n in graph.get_nodes():
                lines.append(f"  {n.get_prim_path()}  ({n.get_type_name()})")
            lines.insert(0, f"SELFTEST 통과 - 노드 {len(lines)}개")
        except Exception as exc:  # noqa: BLE001
            lines.append(f"SELFTEST 실패: {exc}")
        with open("/tmp/forklift_selftest.txt", "w") as fp:
            fp.write("\n".join(lines) + "\n")
        simulation_app.close()
        return

    # 시뮬레이션 루프. 창을 닫거나 Ctrl+C 할 때까지 계속 돈다.
    while simulation_app.is_running():
        world.step(render=True)

    simulation_app.close()


if __name__ == "__main__":
    main()
