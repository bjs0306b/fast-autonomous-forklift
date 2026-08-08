"""Drive the real vehicle on the simulator's map instead of building one.

Same stack as field_slam_nav2.launch.py with slam_toolbox swapped for
map_server and a fixed map->odom. The point is a single coordinate system:
a spot in the simulator and the same spot in the mockup get the same numbers,
so poses can be handed across without anyone converting anything.

    ros2 launch forklift_teleop field_static_map.launch.py \\
        nav2_params_file:=<ws>/nav2_params.yaml drive_enabled:=true

⚠️ **시작 위치가 곧 좌표계의 기준이다.** odom 은 EKF 가 켜지는 순간을 원점으로
   잡으므로, 차를 놓은 자리가 틀리면 **모든 좌표가 통째로 밀린다**. 기본값은
   isaac_sim/nav2/README.md 의 SIM_F02 시작 위치 -- 시뮬 (3.0, 2.0) = 실물
   (0.30, 0.20), yaw 0 이다.

   추정이 틀렸으면 RViz 의 **2D Pose Estimate** 로 실제 위치를 찍어 고친다.
   map_odom_publisher 가 /initialpose 를 받아 좌표계를 거기에 맞춘다. 멈춘
   상태에서 고치고 다시 계획할 것 -- 고치면 움직이는 것은 차가 아니라 세계다.

⚠️ **AMCL 을 안 쓴다.** 심 쪽 주석이 이유를 적어 두었다 -- 반복적인 창고에서는
   스캔 정합이 엉뚱한 통로에 걸려 몇 미터씩 어긋난다. 통로가 두 줄뿐인 목업은
   그 위험이 더 크다.

⚠️ **그래서 위치는 순전히 적산이다.** 심에서는 odom 이 정답값이라 이 방식이
   공짜지만, 실물 odom 은 엔코더와 IMU 적산이라 **시간이 갈수록 밀린다**. 짧은
   임무에는 쓸 만하고 긴 주행에는 아니다. 밀림이 보이면 차를 시작 지점에 다시
   놓고 재기동하는 것이 가장 확실하다.

지도를 새로 만들려면 field_slam_nav2.launch.py 를 쓴다. 둘을 같이 띄우면
map->odom 을 둘이 발행해 자세가 튄다 -- 2026-08-08 에 EKF 가 세 개 떠서 겪은
것과 같은 고장이다.
"""

import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import (
    DeclareLaunchArgument,
    IncludeLaunchDescription,
    SetEnvironmentVariable,
)
from launch.conditions import IfCondition
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue
from nav2_common.launch import RewrittenYaml


def _repo_root(start: str) -> str:
    """Walk up until the directory holding isaac_sim/ turns up."""
    path = start
    while path != os.path.dirname(path):
        if os.path.isdir(os.path.join(path, "isaac_sim")):
            return path
        path = os.path.dirname(path)
    raise RuntimeError(
        "isaac_sim/ 을 못 찾았다 -- map_file 을 직접 넘길 것"
    )


def include(package: str, launch_file: str, **kwargs):
    return IncludeLaunchDescription(
        PythonLaunchDescriptionSource(os.path.join(
            get_package_share_directory(package), "launch", launch_file
        )),
        launch_arguments=kwargs.items(),
    )


def generate_launch_description() -> LaunchDescription:
    teleop_share = get_package_share_directory("forklift_teleop")
    nav2_share = get_package_share_directory("nav2_bringup")

    arguments = [
        # Shared memory off for everything this launch starts; see
        # config/fastdds_no_shm.xml.
        SetEnvironmentVariable(
            "FASTRTPS_DEFAULT_PROFILES_FILE",
            os.path.join(teleop_share, "config", "fastdds_no_shm.xml"),
        ),
        DeclareLaunchArgument(
            "nav2_params_file",
            description="Absolute path to the field-tuned nav2_params.yaml",
        ),
        DeclareLaunchArgument(
            "map_file",
            # <repo>/ros2_ws/install/forklift_teleop/share/forklift_teleop
            # 에서 저장소 루트까지 다섯 단계다. 세면 틀리기 쉬워서 이름으로
            # 올라간다 -- 경로가 바뀌어도 여기서 조용히 어긋나지 않는다.
            default_value=os.path.join(
                _repo_root(teleop_share),
                "isaac_sim", "nav2", "maps", "sim_warehouse_real.yaml",
            ),
            description="시뮬 지도의 실물 축척 버전 (_real 이 붙은 쪽)",
        ),
        DeclareLaunchArgument("drive_enabled", default_value="false"),
        # 시작 지점의 map 좌표. 차를 물리적으로 여기에 놓고 켠다.
        DeclareLaunchArgument("start_x_m", default_value="0.30"),
        DeclareLaunchArgument("start_y_m", default_value="0.20"),
        DeclareLaunchArgument("start_yaw_rad", default_value="0.0"),
    ]

    configured_nav2 = RewrittenYaml(
        source_file=LaunchConfiguration("nav2_params_file"),
        param_rewrites={
            "default_nav_to_pose_bt_xml": os.path.join(
                teleop_share, "behavior_trees",
                "navigate_to_pose_replanning.xml",
            ),
        },
        convert_types=True,
    )

    sensors = include("forklift_teleop", "sensor_usb.launch.py")
    lidar_odom = include("forklift_teleop", "lidar_odometry.launch.py")

    # map_server on its own lifecycle manager, exactly as the simulator does.
    # Under nav2's localization_launch it would come up beside AMCL, and
    # killing AMCL afterwards makes the manager tear map_server down with it.
    map_server = Node(
        package="nav2_map_server",
        executable="map_server",
        name="map_server",
        output="screen",
        parameters=[{
            "yaml_filename": LaunchConfiguration("map_file"),
            "use_sim_time": False,
        }],
    )
    map_manager = Node(
        package="nav2_lifecycle_manager",
        executable="lifecycle_manager",
        name="lifecycle_manager_map",
        output="screen",
        parameters=[{
            "use_sim_time": False,
            "autostart": True,
            "node_names": ["map_server"],
        }],
    )

    # map -> odom. odom 은 EKF 가 켜지는 순간의 차량 위치이므로, 이 변환이 곧
    # "차를 어디에 놓고 켰는가" 를 나타낸다.
    #
    # static_transform_publisher 가 아닌 이유: 그러면 시작 위치 추정이 영구히
    # 박히고, 틀렸어도 알 방법이 없다 -- 지도는 여전히 자기들끼리 아귀가 맞기
    # 때문이다. 이 노드는 /initialpose 를 받아 고칠 수 있다(RViz 의
    # 2D Pose Estimate).
    map_to_odom = Node(
        package="forklift_teleop",
        executable="map_odom_publisher",
        name="map_odom_publisher",
        output="screen",
        parameters=[{
            "start_x_m": ParameterValue(
                LaunchConfiguration("start_x_m"), value_type=float),
            "start_y_m": ParameterValue(
                LaunchConfiguration("start_y_m"), value_type=float),
            "start_yaw_rad": ParameterValue(
                LaunchConfiguration("start_yaw_rad"), value_type=float),
        }],
    )

    nav2 = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(
            os.path.join(nav2_share, "launch", "navigation_launch.py")
        ),
        launch_arguments={
            "params_file": configured_nav2,
            "use_sim_time": "false",
            "autostart": "true",
        }.items(),
    )
    guarded_drive = IncludeLaunchDescription(
        PythonLaunchDescriptionSource(
            os.path.join(teleop_share, "launch", "obstacle_avoidance.launch.py")
        ),
        condition=IfCondition(LaunchConfiguration("drive_enabled")),
    )

    return LaunchDescription([
        *arguments, sensors, lidar_odom, map_server, map_manager,
        map_to_odom, nav2, guarded_drive,
    ])
