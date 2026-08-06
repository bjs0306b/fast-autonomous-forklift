"""Nav2 navigation 스택을 지정 네임스페이스 안으로 강제로 밀어넣는 launch.

humble 의 navigation_launch.py 는 namespace 인자를 줘도 노드에 확실히
안 붙는 경우가 있다. PushRosNamespace 로 감싸면 그 안의 모든 노드가
/{ns}/controller_server 처럼 확실히 네임스페이스 아래로 들어간다.

사용:
  ros2 launch /home/ubuntu/forklift_ws/nav2/scripts/ns_nav2_launch.py \
      namespace:=sim_f02 params_file:=.../nav2_sim.yaml
"""
import os
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, GroupAction, IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import PushRosNamespace, SetRemap
from ament_index_python.packages import get_package_share_directory


def generate_launch_description():
    ns = LaunchConfiguration("namespace")
    params = LaunchConfiguration("params_file")

    nav2_launch = os.path.join(
        get_package_share_directory("nav2_bringup"),
        "launch", "navigation_launch.py",
    )

    return LaunchDescription([
        DeclareLaunchArgument("namespace", default_value="sim_f02"),
        DeclareLaunchArgument("params_file", default_value=""),
        GroupAction([
            PushRosNamespace(ns),
            # TF 는 전역이어야 한다. 네임스페이스로 밀면 /sim_f02/tf 가 되어
            # kinematic_vehicle.py 가 /tf 로 발행한 프레임을 Nav2 가 못 본다.
            # 절대경로 remap 으로 /tf, /tf_static 을 네임스페이스 밖으로 되돌린다.
            SetRemap(src="/tf", dst="/tf"),
            SetRemap(src="/tf_static", dst="/tf_static"),
            # 맵도 전역이어야 한다. 하나의 map_server 가 /map 에 발행하고
            # 모든 차량이 그걸 공유한다. 네임스페이스로 밀면 /sim_f02/map 이
            # 되어 못 받는다("no map received").
            SetRemap(src="/map", dst="/map"),
            IncludeLaunchDescription(
                PythonLaunchDescriptionSource(nav2_launch),
                launch_arguments={
                    "use_sim_time": "True",
                    "params_file": params,
                    "autostart": "True",
                }.items(),
            ),
        ]),
    ])
