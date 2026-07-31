#!/usr/bin/env python3
"""F.A.S.T. 시뮬 지게차 씬을 통째로 구성한다.

GUI 로 손수 만들면 크래시나 덮어쓰기로 날아간다. 이 스크립트는 씬 전체를
매번 같은 상태로 재생성하므로, 날아가도 다시 실행하면 1 분 안에 복구된다.

만드는 것:
  - 창고 환경 (선반 있는 버전 — 라이다가 볼 특징이 필요하다)
  - ForkliftB 지게차
  - PhysX 회전 라이다 (RTX 라이다는 헤드리스에서 레이캐스트가 동작하지 않음)
  - OmniGraph: 구동 / 관절상태 / 시계 / 라이다 / 오도메트리

발행 토픽:
  {NS}/joint_states   {NS}/raw/scan   {NS}/raw/odom   /clock
구독 토픽:
  {NS}/joint_command

실행 (서버):
  ROS 환경변수를 지우고 Isaac 내장 라이브러리를 쓰도록 실행해야 한다.
  run_scene.sh 참고.

이 파일은 자체 완결형이다. 서버에 이 파일 하나만 복사하면 된다.
"""
import os

from isaacsim import SimulationApp

HEADLESS = os.environ.get("HEADLESS", "1") == "1"
simulation_app = SimulationApp({"headless": HEADLESS})

# SimulationApp 생성 이후에만 아래를 import 할 수 있다 (Isaac 의 규칙).
import omni.graph.core as og  # noqa: E402
import omni.kit.commands  # noqa: E402
from isaacsim.core.api import World  # noqa: E402
from isaacsim.core.utils.extensions import enable_extension  # noqa: E402
from isaacsim.core.utils.stage import add_reference_to_stage  # noqa: E402
from pxr import Gf  # noqa: E402

# ---------------------------------------------------------------------------
# 설정
# ---------------------------------------------------------------------------
# 차량 네임스페이스.
# 주의: ROS2 이름 규칙은 [a-zA-Z_][a-zA-Z0-9_]* 이다. 하이픈(-)을 쓰면
#       "topic name is invalid" 로 거부된다. MQTT 쪽 ID 가 SIM-F01 이라면
#       ROS2 는 SIM_F01 을 쓰고 MQTT 경계에서 이름을 바꿔야 한다.
NS = os.environ.get("VEHICLE_NS", "SIM_F01")
SCALE = float(os.environ.get("SCALE", "10"))   # 실물 = 시뮬 / SCALE

ASSETS = "https://omniverse-content-production.s3-us-west-2.amazonaws.com/Assets/Isaac/5.1/Isaac"
WAREHOUSE_USD = f"{ASSETS}/Environments/Simple_Warehouse/warehouse_multiple_shelves.usd"
FORKLIFT_USD = f"{ASSETS}/Robots/IsaacSim/ForkliftB/forklift_b_sensor.usd"

PRIM_WAREHOUSE = "/World/Warehouse"
PRIM_FORKLIFT = "/World/Forklift"
# 라이다는 지게차 루트 바로 아래에 붙인다.
# body/sensors 아래에 붙이려 했으나, 레퍼런스가 합성되기 전이라 부모 경로가
# 존재하지 않아 생성이 조용히 실패했다. 루트는 항상 존재한다.
# body 는 루트 기준 항등 변환이므로 좌표값은 그대로 유효하다.
PRIM_LIDAR = PRIM_FORKLIFT + "/lidar"

# 지게차를 창고 안 빈 자리에 놓는다. 벽 안쪽이어야 라이다가 뭔가를 본다.
FORKLIFT_POS = Gf.Vec3d(0.0, 0.0, 0.3)

# 라이다 장착 위치 (지게차 로컬). 에셋의 front_2d_lidar 자리와 동일.
LIDAR_POS = Gf.Vec3d(0.93, 0.0, 0.1)

# YDLIDAR X4-Pro 사양에 맞춘 값. 시뮬 세계가 SCALE 배 크므로 사거리도 그만큼 곱한다.
# 그래야 실물과 "상대적으로 같은 시야"가 된다.
LIDAR_MIN_RANGE_REAL_M = 0.10
LIDAR_MAX_RANGE_REAL_M = 12.0
LIDAR_ROTATION_HZ = 10.0
LIDAR_H_RESOLUTION_DEG = 0.5

# 지게차의 전진 방향. ForkliftB 는 포크가 −X 쪽에 있고, 포크 쪽이 앞이다.
# 조향륜(back_wheel_swivel)은 +X 쪽 → 후륜 조향. 실물과 동일한 구조.
ROBOT_FRONT = [-1.0, 0.0, 0.0]

OUT_USD = os.path.expanduser(os.environ.get("OUT_USD", "~/fast_scene.usd"))


def build_scene(world):
    """창고 + 지게차 + 라이다를 스테이지에 올린다."""
    add_reference_to_stage(usd_path=WAREHOUSE_USD, prim_path=PRIM_WAREHOUSE)
    add_reference_to_stage(usd_path=FORKLIFT_USD, prim_path=PRIM_FORKLIFT)

    # 레퍼런스가 실제로 합성될 때까지 몇 프레임 돌린다.
    # 이걸 건너뛰면 아래 라이다 생성 시 부모 프림이 아직 없어서 조용히 실패한다.
    for _ in range(5):
        simulation_app.update()

    stage = world.stage
    fork = stage.GetPrimAtPath(PRIM_FORKLIFT)
    fork.GetAttribute("xformOp:translate").Set(FORKLIFT_POS)

    # PhysX 회전 라이다. RTX 라이다는 렌더 파이프라인에 의존해서
    # --no-window 헤드리스에서 레이캐스트가 동작하지 않는다(전부 -1 반환).
    # PhysX 라이다는 물리 레이캐스트라 헤드리스에서도 확실히 동작하고 더 가볍다.
    ok, _prim = omni.kit.commands.execute(
        "RangeSensorCreateLidar",
        path="lidar",
        parent=PRIM_FORKLIFT,
        translation=LIDAR_POS,
        min_range=LIDAR_MIN_RANGE_REAL_M * SCALE,
        max_range=LIDAR_MAX_RANGE_REAL_M * SCALE,
        draw_points=False,
        draw_lines=False,
        horizontal_fov=360.0,
        vertical_fov=1.0,                      # 2D 라이다이므로 최소값
        horizontal_resolution=LIDAR_H_RESOLUTION_DEG,
        vertical_resolution=1.0,
        rotation_rate=LIDAR_ROTATION_HZ,
        high_lod=False,                        # False = 수평 한 줄 (2D)
        yaw_offset=0.0,
        enable_semantics=False,
    )

    # 생성 실패는 조용히 지나가므로 명시적으로 확인한다.
    if not ok or not stage.GetPrimAtPath(PRIM_LIDAR).IsValid():
        raise RuntimeError(f"라이다 생성 실패: {PRIM_LIDAR}")


def build_graph():
    """ROS2 브리지 그래프를 만든다. GUI 로 잇던 연결을 코드로 옮긴 것."""
    og.Controller.edit(
        {"graph_path": "/ActionGraph", "evaluator_name": "execution"},
        {
            og.Controller.Keys.CREATE_NODES: [
                ("OnTick", "omni.graph.action.OnPlaybackTick"),
                ("Context", "isaacsim.ros2.bridge.ROS2Context"),
                ("SimTime", "isaacsim.core.nodes.IsaacReadSimulationTime"),
                # 구동
                ("SubJoint", "isaacsim.ros2.bridge.ROS2SubscribeJointState"),
                ("ArtCtrl", "isaacsim.core.nodes.IsaacArticulationController"),
                # 상태 발행
                ("PubClock", "isaacsim.ros2.bridge.ROS2PublishClock"),
                ("PubJoint", "isaacsim.ros2.bridge.ROS2PublishJointState"),
                # 라이다
                ("ReadLidar", "isaacsim.sensors.physx.IsaacReadLidarBeams"),
                ("PubScan", "isaacsim.ros2.bridge.ROS2PublishLaserScan"),
                # 오도메트리
                ("Odom", "isaacsim.core.nodes.IsaacComputeOdometry"),
                ("PubOdom", "isaacsim.ros2.bridge.ROS2PublishOdometry"),
            ],
            og.Controller.Keys.CONNECT: [
                # --- 매 프레임 실행 ---
                ("OnTick.outputs:tick", "SubJoint.inputs:execIn"),
                ("OnTick.outputs:tick", "PubClock.inputs:execIn"),
                ("OnTick.outputs:tick", "PubJoint.inputs:execIn"),
                ("OnTick.outputs:tick", "ReadLidar.inputs:execIn"),
                ("OnTick.outputs:tick", "Odom.inputs:execIn"),
                # --- ROS2 컨텍스트 ---
                ("Context.outputs:context", "SubJoint.inputs:context"),
                ("Context.outputs:context", "PubClock.inputs:context"),
                ("Context.outputs:context", "PubJoint.inputs:context"),
                ("Context.outputs:context", "PubScan.inputs:context"),
                ("Context.outputs:context", "PubOdom.inputs:context"),
                # --- 시뮬 시간 ---
                ("SimTime.outputs:simulationTime", "PubClock.inputs:timeStamp"),
                ("SimTime.outputs:simulationTime", "PubJoint.inputs:timeStamp"),
                ("SimTime.outputs:simulationTime", "PubScan.inputs:timeStamp"),
                ("SimTime.outputs:simulationTime", "PubOdom.inputs:timeStamp"),
                # --- 관절 지령 → 제어기 (이름이 1:1 로 대응) ---
                ("SubJoint.outputs:execOut", "ArtCtrl.inputs:execIn"),
                ("SubJoint.outputs:jointNames", "ArtCtrl.inputs:jointNames"),
                ("SubJoint.outputs:positionCommand", "ArtCtrl.inputs:positionCommand"),
                ("SubJoint.outputs:velocityCommand", "ArtCtrl.inputs:velocityCommand"),
                # --- 라이다 → LaserScan (이름이 1:1 로 대응) ---
                ("ReadLidar.outputs:execOut", "PubScan.inputs:execIn"),
                ("ReadLidar.outputs:azimuthRange", "PubScan.inputs:azimuthRange"),
                ("ReadLidar.outputs:depthRange", "PubScan.inputs:depthRange"),
                ("ReadLidar.outputs:horizontalFov", "PubScan.inputs:horizontalFov"),
                ("ReadLidar.outputs:horizontalResolution", "PubScan.inputs:horizontalResolution"),
                ("ReadLidar.outputs:intensitiesData", "PubScan.inputs:intensitiesData"),
                ("ReadLidar.outputs:linearDepthData", "PubScan.inputs:linearDepthData"),
                ("ReadLidar.outputs:numCols", "PubScan.inputs:numCols"),
                ("ReadLidar.outputs:numRows", "PubScan.inputs:numRows"),
                ("ReadLidar.outputs:rotationRate", "PubScan.inputs:rotationRate"),
                # --- 오도메트리 → Odometry ---
                ("Odom.outputs:execOut", "PubOdom.inputs:execIn"),
                ("Odom.outputs:position", "PubOdom.inputs:position"),
                ("Odom.outputs:orientation", "PubOdom.inputs:orientation"),
                ("Odom.outputs:linearVelocity", "PubOdom.inputs:linearVelocity"),
                ("Odom.outputs:angularVelocity", "PubOdom.inputs:angularVelocity"),
            ],
            og.Controller.Keys.SET_VALUES: [
                ("SubJoint.inputs:topicName", "joint_command"),
                ("SubJoint.inputs:nodeNamespace", NS),
                ("PubJoint.inputs:topicName", "joint_states"),
                ("PubJoint.inputs:nodeNamespace", NS),
                ("PubJoint.inputs:targetPrim", [PRIM_FORKLIFT]),
                ("ArtCtrl.inputs:robotPath", PRIM_FORKLIFT),
                ("PubScan.inputs:topicName", "raw/scan"),
                ("PubScan.inputs:nodeNamespace", NS),
                ("PubScan.inputs:frameId", "laser_frame"),
                ("ReadLidar.inputs:lidarPrim", [PRIM_LIDAR]),
                ("Odom.inputs:chassisPrim", [PRIM_FORKLIFT]),
                ("PubOdom.inputs:topicName", "raw/odom"),
                ("PubOdom.inputs:nodeNamespace", NS),
                ("PubOdom.inputs:chassisFrameId", "base_link"),
                ("PubOdom.inputs:odomFrameId", "odom"),
                # 포크 쪽(−X)이 전진 방향. 이 값으로 속도가 로봇 기준으로 투영된다.
                ("PubOdom.inputs:robotFront", ROBOT_FRONT),
            ],
        },
    )


def main():
    enable_extension("isaacsim.ros2.bridge")
    enable_extension("isaacsim.sensors.physx")
    simulation_app.update()

    world = World(stage_units_in_meters=1.0)
    build_scene(world)
    build_graph()
    world.reset()

    world.stage.GetRootLayer().Export(OUT_USD)

    lines = [
        "씬 구성 완료",
        f"  네임스페이스 : {NS}",
        f"  축척         : 실물 = 시뮬 / {SCALE}",
        f"  라이다 사거리 : {LIDAR_MAX_RANGE_REAL_M * SCALE} m (시뮬) "
        f"= {LIDAR_MAX_RANGE_REAL_M} m (실물)",
        f"  저장         : {OUT_USD}",
        "",
        "확인:",
        f"  ros2 topic echo /{NS}/raw/scan --once",
        f"  ros2 topic echo /{NS}/raw/odom --once",
        "움직이기:",
        f"  ros2 topic pub -r 20 /{NS}/joint_command sensor_msgs/msg/JointState \\",
        '    "{name: [back_wheel_drive], velocity: [-6.0]}"',
    ]
    # Isaac 이 stdout 을 가로채므로 파일로도 남긴다.
    with open("/tmp/fast_scene_result.txt", "w") as fp:
        fp.write("\n".join(lines) + "\n")
    print("\n" + "\n".join(lines) + "\n")

    # render=True 로 둔다. 헤드리스에서도 마찬가지다.
    # PhysX 라이다는 업데이트 파이프라인에서 스캔을 진행하므로 render=False 면
    # 스캔이 완료되지 않아 ReadLidar 의 execOut 이 울리지 않고, 결과적으로
    # LaserScan 발행자가 생성되지 않아 /raw/scan 토픽 자체가 나타나지 않는다.
    # (관절·오도메트리는 물리 스텝에서 직접 나오므로 영향을 받지 않는다)
    while simulation_app.is_running():
        world.step(render=True)

    simulation_app.close()


if __name__ == "__main__":
    main()
