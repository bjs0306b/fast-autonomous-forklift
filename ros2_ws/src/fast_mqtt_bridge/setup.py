from glob import glob
from setuptools import find_packages, setup


package_name = "fast_mqtt_bridge"


setup(
    name=package_name,
    version="0.1.0",
    packages=find_packages(exclude=["test"]),
    data_files=[
        ("share/ament_index/resource_index/packages", ["resource/" + package_name]),
        ("share/" + package_name, ["package.xml"]),
        ("share/" + package_name + "/config", glob("config/*.yaml")),
        ("share/" + package_name + "/launch", glob("launch/*.launch.py")),
    ],
    install_requires=["setuptools", "paho-mqtt>=1.6"],
    zip_safe=True,
    maintainer="FAST Team",
    maintainer_email="dev@fast.local",
    description="Backend-compatible ROS2 to MQTT bridge for FAST forklifts.",
    license="Apache-2.0",
    tests_require=["pytest"],
    entry_points={
        "console_scripts": [
            "mqtt_bridge = fast_mqtt_bridge.mqtt_bridge_node:main",
            "orin_telemetry = fast_mqtt_bridge.orin_telemetry:main",
            "sim_task_receiver = "
            "fast_mqtt_bridge.sim_task_receiver:main",
        ],
    },
)
