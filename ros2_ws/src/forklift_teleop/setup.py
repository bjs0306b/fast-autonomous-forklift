from glob import glob
from setuptools import find_packages, setup


package_name = "forklift_teleop"


setup(
    name=package_name,
    version="0.1.0",
    packages=find_packages(exclude=["test"]),
    data_files=[
        ("share/ament_index/resource_index/packages", ["resource/" + package_name]),
        ("share/" + package_name, ["package.xml"]),
        ("share/" + package_name + "/config", glob("config/*.yaml")),
        (
            "share/" + package_name + "/config",
            glob("config/*.rviz"),
        ),
        ("share/" + package_name + "/launch", glob("launch/*.launch.py")),
        (
            "share/" + package_name + "/behavior_trees",
            glob("behavior_trees/*.xml"),
        ),
    ],
    install_requires=["setuptools"],
    zip_safe=True,
    maintainer="FAST Team",
    maintainer_email="dev@fast.local",
    description=(
        "Rear-steered forklift tele-operation UART bridge and ESP32 sensor "
        "telemetry bridge."
    ),
    license="Apache-2.0",
    tests_require=["pytest"],
    entry_points={
        "console_scripts": [
            "uart_teleop_bridge = forklift_teleop.uart_teleop_bridge:main",
            "sensor_bridge = forklift_teleop.sensor_bridge:main",
            "obstacle_avoidance = "
            "forklift_teleop.obstacle_avoidance_node:main",
            "unmanned_mission = forklift_teleop.unmanned_mission:main",
            "field_lap_mission = forklift_teleop.field_lap_mission:main",
        ],
    },
)
