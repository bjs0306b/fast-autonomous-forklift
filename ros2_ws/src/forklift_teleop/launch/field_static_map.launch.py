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

localization 인자로 둘 중 하나를 고른다. **택일이고, 둘 다 map->odom 을 발행
하므로 같이 띄우면 자세가 튄다.**

  localization:=manual  기본값. map_odom_publisher 가 발행하고 사람이 RViz 의
                        2D Pose Estimate 로 고친다. 추적을 안 하므로 엉뚱한
                        통로에 걸릴 일이 없는 대신 **밀림도 안 잡힌다** --
                        실물 odom 은 엔코더·IMU 적산이라 시간이 갈수록 밀린다.

  localization:=amcl    스캔 정합으로 계속 추적한다. 밀림을 잡아 주지만 이
                        지도에서 잘 될지는 해봐야 안다 -- 시뮬 지도는 선반을
                        꽉 찬 덩어리로 그리는데 실물 선반은 열린 구조라 **빔의
                        46% 가 지도상 빈 공간에 떨어진다**(2026-08-08 실측).

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
from launch.conditions import IfCondition, UnlessCondition
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration, PythonExpression
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
        #
        # ⚠️ 이미 걸려 있는 값을 **덮어쓰지 않는다.** 종전에는 무조건 덮어써서,
        #    원격 RViz 용으로 다른 프로파일을 export 해도 launch 가 조용히
        #    되돌려 놓았다. 무엇이 적용됐는지는 밖에서 안 보이므로 "설정을
        #    바꿨는데 아무 변화가 없다" 로만 나타난다.
        #
        #    원격에서 볼 때는 fastdds_remote.xml 을 쓴다 -- 오린이 랜 주소만
        #    광고하게 해서, 노트북이 도커·VPN 주소로 답하려다 실패하는 것을
        #    막는다. RELIABLE 토픽만 안 오고 /scan(BEST_EFFORT)은 오는 증상이
        #    정확히 그것이었다.
        SetEnvironmentVariable(
            "FASTRTPS_DEFAULT_PROFILES_FILE",
            os.environ.get(
                "FASTRTPS_DEFAULT_PROFILES_FILE",
                os.path.join(teleop_share, "config", "fastdds_no_shm.xml"),
            ),
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
        # manual: map_odom_publisher (사람이 RViz 로 고침, 추적 없음)
        # amcl:   스캔 정합으로 계속 추적
        #
        # ⚠️ 택일이다. 둘 다 map->odom 을 발행하므로 같이 띄우면 TF 리스너가
        #    마지막에 온 것을 쓰면서 자세가 두 값 사이를 튄다 -- 2026-08-08 에
        #    EKF 세 개로 겪은 것과 같은 고장이고, 센서를 봐서는 안 보인다.
        DeclareLaunchArgument("localization", default_value="manual"),
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
        condition=UnlessCondition(
            PythonExpression(
                ["'", LaunchConfiguration("localization"), "' == 'amcl'"]
            )
        ),
        parameters=[{
            "start_x_m": ParameterValue(
                LaunchConfiguration("start_x_m"), value_type=float),
            "start_y_m": ParameterValue(
                LaunchConfiguration("start_y_m"), value_type=float),
            "start_yaw_rad": ParameterValue(
                LaunchConfiguration("start_yaw_rad"), value_type=float),
        }],
    )

    # ⚠️ AMCL 이 이 지도에서 잘 될지는 해봐야 안다. 스캔이 지도와 맞는 정도로
    #    위치를 고르는데, 시뮬 지도는 선반을 꽉 찬 덩어리로 그리고 실물 선반은
    #    열린 구조라 **빔의 46% 가 지도상 빈 공간에 떨어진다**(2026-08-08 실측).
    #    그만큼 정보가 줄어 벽에만 의존하게 되는데, 2 x 3 m 상자에서는 그것으로
    #    충분할 수도 있다.
    #
    #    manual 쪽과 비교해 보고 고를 것. 밀림이 쌓이는 긴 주행에는 AMCL 이,
    #    한 번 맞춰 놓고 짧게 도는 데는 manual 이 낫다.
    amcl = Node(
        package="nav2_amcl",
        executable="amcl",
        name="amcl",
        output="screen",
        condition=IfCondition(
            PythonExpression(
                ["'", LaunchConfiguration("localization"), "' == 'amcl'"]
            )
        ),
        parameters=[{
            "use_sim_time": False,
            "base_frame_id": "base_link",
            "odom_frame_id": "odom",
            "global_frame_id": "map",
            "scan_topic": "/scan",
            "set_initial_pose": True,
            "initial_pose.x": ParameterValue(
                LaunchConfiguration("start_x_m"), value_type=float),
            "initial_pose.y": ParameterValue(
                LaunchConfiguration("start_y_m"), value_type=float),
            "initial_pose.yaw": ParameterValue(
                LaunchConfiguration("start_yaw_rad"), value_type=float),
            # 목업이 2 x 3 m 라 라이다가 사방 벽을 짧은 거리에서 본다. 기본값
            # (12 m)은 이 공간에 비해 지나치게 멀다.
            "laser_max_range": 4.0,
            "laser_min_range": 0.1,
            "max_particles": 2000,
            "min_particles": 500,
            # 이 차는 후륜 조향이지만 AMCL 의 운동 모델은 전륜 기준 이름뿐이고,
            # 실제로는 odom 증분만 쓴다.
            "robot_model_type": "nav2_amcl::DifferentialMotionModel",
            "update_min_d": 0.05,
            "update_min_a": 0.1,
        }],
    )
    amcl_manager = Node(
        package="nav2_lifecycle_manager",
        executable="lifecycle_manager",
        name="lifecycle_manager_amcl",
        output="screen",
        condition=IfCondition(
            PythonExpression(
                ["'", LaunchConfiguration("localization"), "' == 'amcl'"]
            )
        ),
        parameters=[{
            "use_sim_time": False,
            "autostart": True,
            "node_names": ["amcl"],
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
        map_to_odom, amcl, amcl_manager, nav2, guarded_drive,
    ])
