"""지게차용 Nav2 기동 — 기본 bringup 에 Ackermann 복구 트리를 끼워 넣는다 (S15P11A304-193).

    ros2 launch forklift_teleop nav2_forklift.launch.py \
        map:=$HOME/S15P11A304/ros2_ws/maps/sim_warehouse_real.yaml

## 무엇이 문제였나 — 트리는 있었는데 안 쓰이고 있었다

`navigate_to_pose_no_spin.xml` 은 **2026-07-31부터 레포에 있었다.** 그런데
`nav2_params.yaml` 의 `default_nav_to_pose_bt_xml` 이 주석 처리돼 있어 아무도
그걸 지정하지 않았고, `nav2_bringup bringup_launch.py` 를 직접 부르면 Nav2 는
**기본 트리**를 쓴다. 기본 트리엔 `<Spin>` 이 들어 있다.

`Spin` 은 이 차량에서 **반드시 실패한다** — 모터 브리지가 전진 성분 없는 명령을
데드밴드로 걸러 아무 동작도 안 하기 때문이다(`mapping.py:map_twist`). 뒷바퀴
조향은 제자리 회전이 불가능하므로 그 처리가 옳다.

2026-08-03 젯슨 실물에서 `behavior_server: spin failed` 로 확인했다 — 즉
**준비된 안전장치가 배선이 빠져 안 걸리고 있었다.**

## 왜 절대경로를 params 에 박지 않는가

레포가 기계마다 다른 곳에 있다(`~/ros2_ws` vs `~/S15P11A304/ros2_ws`). 절대경로를
`nav2_params.yaml` 에 적으면 다른 기계에서 조용히 깨진다 — 파일이 없으면 Nav2 는
**기본 트리로 되돌아가고 그 사실을 크게 알리지 않는다.** 그래서 `find-pkg-share` 로
설치 위치에서 찾아 넣는다.

⚠️ `RewrittenYaml` 은 **이미 있는 키만** 덮어쓴다. 그래서 `nav2_params.yaml` 의
`bt_navigator` 에 `default_nav_to_pose_bt_xml` 자리를 비워 두었다. 그 줄을 지우면
이 launch 가 조용히 무력해진다.
"""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration
from nav2_common.launch import RewrittenYaml


def generate_launch_description() -> LaunchDescription:
    teleop_share = get_package_share_directory("forklift_teleop")
    nav2_share = get_package_share_directory("nav2_bringup")

    # 재계획·복구 트리. 앞선 navigate_to_pose_no_spin.xml 은 경로를 한 번만
    # 계산하고 실패하면 즉시 abort 해서, 장애물 앞에 멈춘 뒤 아무것도 하지
    # 않았다. 되돌릴 때를 대비해 옛 트리도 레포에 남겨 둔다.
    bt_xml = os.path.join(teleop_share, "behavior_trees",
                          "navigate_to_pose_replanning.xml")

    map_yaml = LaunchConfiguration("map")
    params_file = LaunchConfiguration("params_file")
    use_sim_time = LaunchConfiguration("use_sim_time")

    configured_params = RewrittenYaml(
        source_file=params_file,
        root_key="",
        param_rewrites={"default_nav_to_pose_bt_xml": bt_xml},
        convert_types=True,
    )

    return LaunchDescription([
        DeclareLaunchArgument("map", description="맵 yaml 경로 (필수)"),
        # ⚠️ 기본값을 두지 않는다. 패키지 안에 사본을 두면 워크스페이스 루트의
        #    nav2_params.yaml 과 **두 벌이 되어 갈라진다.** 튜닝 값이 어느 쪽에
        #    들어갔는지 모르게 되는 게 이 프로젝트에서 반복된 사고다.
        DeclareLaunchArgument("params_file", description="nav2_params.yaml 경로 (필수)"),
        # 실물 주행이므로 시뮬 시간을 쓰지 않는다. 켜면 TF 타임스탬프가 어긋나
        # 코스트맵이 "transform timeout" 으로 조용히 비어 버린다.
        DeclareLaunchArgument("use_sim_time", default_value="false"),

        IncludeLaunchDescription(
            PythonLaunchDescriptionSource(
                os.path.join(nav2_share, "launch", "bringup_launch.py")),
            launch_arguments={
                "map": map_yaml,
                "params_file": configured_params,
                "use_sim_time": use_sim_time,
            }.items(),
        ),
    ])
