from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch_ros.actions import Node
import os


# Vehicle coordinate convention, matching lidar_odometry.launch.py:
#   base_link: center of the front drive axle
#   +X: fork direction, +Y: left, +Z: up
#
# 실측값 (2026-08-05). base_link = 전륜 구동축 중심, +X 포크, +Y 좌, +Z 위.
#
# base_link 의 z 원점은 **축 중심이며 바닥보다 30 mm 위**다. ToF 를 축 기준
# 40 mm / 바닥 기준 70 mm 로 두 번 잰 값의 차이가 그 높이다. 이 규약을 모르면
# 세 센서의 z 가 전부 30 mm 씩 어긋난다.
#
# 교차 확인: LiDAR z=0.202 이므로 바닥에서 0.232 m 여야 한다.
# ToF 브래킷은 조절이 안 되고 아래로 3도 기울어 있다. 바닥 행에 장착 높이를
# 적합시켜 구한 실측값이다 (절차 D). +Y 축 양의 회전이 +X 를 아래로 내리므로
# 이 부호가 맞다. sensors.yaml 의 tof_pitch_deg 와 항상 같이 움직여야 한다 —
# 이쪽은 반환점의 위치를, 저쪽은 바닥 제거를 담당하며 같은 장착을 기술한다.
# 두 브래킷이 서로 다르게 기울어 있어 값을 따로 둔다. 좌는 아래로 3.0deg,
# 우는 위로 1.1deg (2026-08-06 바닥 행 적합, 둘 다 장착 높이 71.5 mm 로 수렴).
TOF_LEFT_PITCH_RAD = "0.0524"
TOF_RIGHT_PITCH_RAD = "-0.0192"

SENSOR_TRANSFORMS = (
    # (name, parent, child, x, y, z, roll, pitch, yaw)
    # y 는 측정값이 없어 중앙으로 둔다. 좌우로 치우쳐 달았다면 고칠 것.
    ("base_link_to_imu_link", "base_link", "imu_link",
     "-0.055", "0.0", "0.115", "0.0", "0.0", "0.0"),
    # 두 ToF 모두 +X 정면. 벌리지 않는 이유는 정면 근거리 사각 때문이며
    # docs/센서-측정-칼리브레이션.md 2.2 절에 계산이 있다.
    ("base_link_to_tof_left_link", "base_link", "tof_left_link",
     "0.085", "0.050", "0.040", "0.0", TOF_LEFT_PITCH_RAD, "0.0"),
    ("base_link_to_tof_right_link", "base_link", "tof_right_link",
     "0.085", "-0.050", "0.040", "0.0", TOF_RIGHT_PITCH_RAD, "0.0"),
)


def generate_launch_description():
    package_share = get_package_share_directory("forklift_teleop")
    parameters = os.path.join(package_share, "config", "sensors.yaml")

    actions = [
        Node(
            package="forklift_teleop",
            executable="sensor_bridge",
            name="sensor_bridge",
            output="screen",
            parameters=[parameters],
        )
    ]

    for name, parent, child, x, y, z, roll, pitch, yaw in SENSOR_TRANSFORMS:
        actions.append(
            Node(
                package="tf2_ros",
                executable="static_transform_publisher",
                name=name,
                output="screen",
                arguments=[
                    "--x", x,
                    "--y", y,
                    "--z", z,
                    "--roll", roll,
                    "--pitch", pitch,
                    "--yaw", yaw,
                    "--frame-id", parent,
                    "--child-frame-id", child,
                ],
            )
        )

    return LaunchDescription(actions)
