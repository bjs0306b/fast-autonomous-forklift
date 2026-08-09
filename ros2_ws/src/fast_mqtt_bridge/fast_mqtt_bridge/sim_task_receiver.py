"""Take a task from the simulator and hand it to the vehicle in real metres.

The demo is: the simulator names a pallet position, the vehicle drives there
and picks it up; the simulator names a drop-off, the vehicle drives there and
sets it down. Until now the mission node read pickup and dropoff from launch
parameters, so it could only ever run the one errand it was configured with.
This subscribes to the task topic and turns each message into a mission.

    fast/v1/vehicle/{id}/task  ->  /mission/task  (JSON, real metres)

⚠️ **축척 변환은 여기 한 곳에서만 일어난다.** 시뮬 창고는 20 x 30 m 이고 목업은
   2 x 3 m 라 위치는 1/10 이다. interface-spec.md 가 "변환은 MQTT 경계에서만"
   이라고 정한 이유가 이것이다 -- 변환이 두 곳에 있으면 언젠가 한쪽만 고쳐지고,
   그 증상은 "가끔 엉뚱한 데로 간다" 로만 보인다.

⚠️ **각도는 변환하지 않는다.** 축척을 바꿔도 각도는 그대로다.

⚠️ **목업 밖 좌표는 거부한다.** 시뮬 창고 어디든 유효한 좌표이므로, 그대로
   받으면 목업 벽 너머로 목표를 세우게 된다. Nav2 는 갈 수 없는 곳을 향해
   계속 계획을 시도하고, 차는 벽에 붙어 멈춘 채 시간을 보낸다.
"""

import json
import os
from typing import Optional

import paho.mqtt.client as mqtt
import rclpy
from rclpy.node import Node
from std_msgs.msg import String


class SimTaskReceiver(Node):
    def __init__(self, mqtt_client=None) -> None:
        super().__init__("sim_task_receiver")

        self.declare_parameter("mqtt_enabled", False)
        self.declare_parameter("mqtt_host", "i15a304.p.ssafy.io")
        self.declare_parameter("mqtt_port", 8883)
        self.declare_parameter("mqtt_username", "")
        self.declare_parameter("mqtt_password_env", "MQTT_PASSWORD")
        self.declare_parameter("mqtt_client_id", "fk01-orin-task")
        self.declare_parameter("mqtt_keepalive", 30)
        self.declare_parameter("mqtt_ca_cert", "")
        self.declare_parameter("mqtt_tls_insecure", False)
        # 로컬 mosquitto 로 사슬을 시험할 때만 켠다. 운영 브로커는 인증·TLS 다.
        self.declare_parameter("mqtt_allow_anonymous", False)
        self.declare_parameter("task_topic", "fast/v1/vehicle/fk01/task")
        self.declare_parameter("mission_topic", "/mission/task")
        # isaac_sim/nav2/README.md: 시뮬 20 x 30 m, 목업 2 x 3 m.
        self.declare_parameter("sim_scale", 10.0)
        self.declare_parameter("mockup_width_m", 2.0)
        self.declare_parameter("mockup_height_m", 3.0)
        # 벽에 목표를 붙이면 도달할 수 없다. footprint 반폭보다 넉넉히 둔다.
        self.declare_parameter("wall_margin_m", 0.15)

        self._scale = float(self.get_parameter("sim_scale").value)
        if self._scale <= 0.0:
            raise ValueError("sim_scale must be positive")
        self._width = float(self.get_parameter("mockup_width_m").value)
        self._height = float(self.get_parameter("mockup_height_m").value)
        self._margin = float(self.get_parameter("wall_margin_m").value)

        self._publisher = self.create_publisher(
            String, str(self.get_parameter("mission_topic").value), 10)

        self._mqtt = mqtt_client
        if bool(self.get_parameter("mqtt_enabled").value):
            self._start_mqtt()
        else:
            # Off by default so the node is safe to launch everywhere. Feed it
            # by hand while the simulator is not up:
            #   ros2 topic pub --once /mission/task std_msgs/String ...
            self.get_logger().info(
                "mqtt_enabled=false -- 시뮬 연결 없이 뜬다. "
                f"{self.get_parameter('mission_topic').value} 로 직접 넣어도 된다."
            )

    # ------------------------------------------------------------------ MQTT

    def _start_mqtt(self) -> None:
        if self._mqtt is None:
            client_id = str(self.get_parameter("mqtt_client_id").value)
            try:
                self._mqtt = mqtt.Client(
                    callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
                    client_id=client_id, clean_session=True)
            except AttributeError:
                self._mqtt = mqtt.Client(
                    client_id=client_id, clean_session=True)

        # ⚠️ **익명·평문은 명시적으로 켤 때만.** 기본값이 아니어야 하는 이유는,
        #    실수로 켜졌을 때 증상이 없기 때문이다 -- 운영 브로커에 인증 없이
        #    붙으려다 조용히 실패하거나, 더 나쁘게는 붙는다. 로컬 mosquitto 로
        #    사슬 전체를 시험하려면 이 값을 손으로 켜야 한다.
        anonymous = bool(self.get_parameter("mqtt_allow_anonymous").value)
        username = str(self.get_parameter("mqtt_username").value)
        password = os.environ.get(
            str(self.get_parameter("mqtt_password_env").value), "")
        if not anonymous:
            if not username or not password:
                raise ValueError(
                    "MQTT username and password environment variable are "
                    "required (로컬 브로커로 시험하려면 "
                    "mqtt_allow_anonymous:=true)"
                )
            self._mqtt.username_pw_set(username, password)
        elif username:
            self._mqtt.username_pw_set(username, password)

        ca_cert = os.path.expanduser(str(self.get_parameter("mqtt_ca_cert").value))
        if anonymous and not ca_cert:
            self.get_logger().warning(
                "익명·평문 MQTT 로 붙는다 -- 시험용이다. 운영 브로커에는 "
                "mqtt_allow_anonymous 를 켜지 말 것."
            )
        else:
            if not ca_cert or not os.path.isfile(ca_cert):
                raise ValueError("mqtt_ca_cert must point to FAST-MQTT-CA")
            self._mqtt.tls_set(ca_certs=ca_cert)
            insecure = bool(self.get_parameter("mqtt_tls_insecure").value)
            self._mqtt.tls_insecure_set(insecure)
            if insecure:
                self.get_logger().warning(
                    "TLS hostname verification is disabled because the field "
                    "certificate is issued to an IP. Use only with FAST-MQTT-CA."
                )

        self._mqtt.on_connect = self._on_connect
        self._mqtt.on_message = self._on_message
        self._mqtt.reconnect_delay_set(min_delay=1, max_delay=30)
        self._mqtt.connect_async(
            str(self.get_parameter("mqtt_host").value),
            int(self.get_parameter("mqtt_port").value),
            int(self.get_parameter("mqtt_keepalive").value),
        )
        self._mqtt.loop_start()

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        topic = str(self.get_parameter("task_topic").value)
        # QoS 1: interface-spec 이 task 를 QoS 1 로 정했다. 임무 지시를 놓치면
        # 차는 아무 일도 안 하고 서 있고, 아무도 이유를 모른다.
        client.subscribe(topic, qos=1)
        self.get_logger().info(f"Subscribed to {topic}")

    def _on_message(self, client, userdata, message):
        try:
            payload = json.loads(message.payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            self.get_logger().error(f"Task payload is not JSON: {error}")
            return
        self.handle_task(payload)

    # ------------------------------------------------------------ conversion

    def _to_real(self, point: dict, label: str) -> Optional[dict]:
        """Simulator metres to mockup metres, refusing what cannot be reached."""
        try:
            x = float(point["x"]) / self._scale
            y = float(point["y"]) / self._scale
            yaw = float(point.get("yaw", 0.0))
        except (KeyError, TypeError, ValueError) as error:
            self.get_logger().error(f"{label} is malformed: {error}")
            return None

        low, high_x, high_y = (self._margin,
                               self._width - self._margin,
                               self._height - self._margin)
        if not (low <= x <= high_x and low <= y <= high_y):
            self.get_logger().error(
                f"{label} at sim ({point['x']}, {point['y']}) is real "
                f"({x:.3f}, {y:.3f}), outside the mockup "
                f"({low:.2f}~{high_x:.2f}, {low:.2f}~{high_y:.2f}). Refusing."
            )
            return None

        converted = {"x": x, "y": y, "yaw": yaw}
        # 축척은 위치에만 적용한다. 포크 높이도 길이라 함께 나눈다.
        if "forkHeight" in point:
            try:
                converted["forkHeight"] = float(point["forkHeight"]) / self._scale
            except (TypeError, ValueError):
                self.get_logger().warn(f"{label} forkHeight ignored")
        return converted

    def handle_task(self, payload: dict) -> bool:
        """Convert and republish. Returns whether the task was accepted."""
        task_id = str(payload.get("taskId", ""))
        action = str(payload.get("action", "")).upper()

        # ⚠️ **관제는 지점을 하나씩 보낸다** (orin-pose-spec §6.2). 순환로를
        #    따라 다음 지점만 오고, 같은 목표를 되풀이하지 않는다. 그 형식은
        #    action="GOTO" 에 pickup 하나뿐이라, pickup·dropoff 를 둘 다
        #    요구하면 **단순 이동 명령이 통째로 거절된다.** 그러면 증상은
        #    "관제가 좌표를 주는데 차가 안 움직인다" 로만 보인다.
        if action == "GOTO" or (
            "dropoff" not in payload and "pickup" in payload
        ):
            goal = self._to_real(payload.get("pickup") or {}, "goal")
            if goal is None:
                self.get_logger().error(f"Task {task_id or '(no id)'} refused")
                return False
            mission = {
                "taskId": task_id,
                "action": "GOTO",
                "goal": goal,
                "sim": {"goal": payload.get("pickup")},
            }
            self._publisher.publish(String(data=json.dumps(mission)))
            self.get_logger().info(
                f"Task {task_id or '(no id)'} GOTO: "
                f"sim({payload['pickup']['x']}, {payload['pickup']['y']}) "
                f"-> real({goal['x']:.3f}, {goal['y']:.3f}) "
                f"yaw {goal['yaw']:.4f} rad"
            )
            return True

        pickup = self._to_real(payload.get("pickup") or {}, "pickup")
        dropoff = self._to_real(payload.get("dropoff") or {}, "dropoff")
        if pickup is None or dropoff is None:
            self.get_logger().error(f"Task {task_id or '(no id)'} refused")
            return False

        mission = {
            "taskId": task_id,
            "action": "PICKUP_DROPOFF",
            "cargoId": payload.get("cargoId"),
            "pickup": pickup,
            "dropoff": dropoff,
            # 원본을 함께 실어 둔다. 로그에서 "심이 뭘 보냈나" 와 "차가 뭘
            # 받았나" 를 한 줄에서 대조할 수 있어야 변환 실수를 잡는다.
            "sim": {"pickup": payload.get("pickup"),
                    "dropoff": payload.get("dropoff")},
        }
        self._publisher.publish(String(data=json.dumps(mission)))
        self.get_logger().info(
            f"Task {task_id or '(no id)'}: "
            f"pickup sim({payload['pickup']['x']}, {payload['pickup']['y']}) "
            f"-> real({pickup['x']:.3f}, {pickup['y']:.3f}) · "
            f"dropoff sim({payload['dropoff']['x']}, {payload['dropoff']['y']}) "
            f"-> real({dropoff['x']:.3f}, {dropoff['y']:.3f})"
        )
        return True


def main(args=None) -> None:
    rclpy.init(args=args)
    node = SimTaskReceiver()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
