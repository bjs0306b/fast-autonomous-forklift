"""F.A.S.T. 실물/시뮬 지게차 공통 파라미터 — 단일 진실 공급원(single source of truth).

시뮬(Isaac Sim ForkliftB)은 실물 크기, 실물 Orin 카는 그 1/SCALE 축소 모델이다.
축척 변환은 이 모듈의 sim_to_real / real_to_sim 을 통해서만 수행한다.
여러 곳에서 곱하기 시작하면 이중 적용을 추적할 수 없다.

구동 구조 (실물 = 시뮬 동일):
    - 전륜 DC 모터 1개로 구동 (시뮬 ForkliftB는 후륜 구동이지만 기하에는 영향 없음)
    - 후륜 서보 1개로 조향  <- 자동차형(전륜조향) 아님. 지게차형(후륜조향).
    - 포크 승강용 리니어 스텝모터 1개 (Z축 prismatic)
"""

# ---------------------------------------------------------------------------
# 축척
# ---------------------------------------------------------------------------
# 실물 = 시뮬 / SCALE.  TODO(팀): n 확정. 포크 스트로크 또는 축거(wheelbase) 실측비로 결정할 것.
SCALE = 10.0


def sim_to_real(v: float) -> float:
    """시뮬 길이(m) -> 실물 길이(m)."""
    return v / SCALE


def real_to_sim(v: float) -> float:
    """실물 길이(m) -> 시뮬 길이(m)."""
    return v * SCALE


# ---------------------------------------------------------------------------
# 조향 — 실측값으로 교체 필요
# ---------------------------------------------------------------------------
# ForkliftB 기본 back_wheel_swivel 한계는 +-60도. 실물 서보가 그보다 좁으면
# 시뮬만 통과하고 실물은 못 도는 경로가 나온다. 반드시 실물 값으로 조인다.
# TODO(전희창): 링키지 물린 상태의 실제 최대 조향각 실측.
STEER_LIMIT_DEG = 30.0

# ---------------------------------------------------------------------------
# 포크 (실물 기준 m)
# ---------------------------------------------------------------------------
# TODO(전희창): Nema17 스크류 리니어 모터의 실제 행정거리 실측.
FORK_STROKE_REAL_M = 0.20
# 주행 시 포크 높이. 명세서 2.3 운용 규칙: 바닥에서 10~15cm 띄우고 주행.
FORK_TRAVEL_HEIGHT_REAL_M = 0.12

FORK_STROKE_SIM_M = real_to_sim(FORK_STROKE_REAL_M)
FORK_TRAVEL_HEIGHT_SIM_M = real_to_sim(FORK_TRAVEL_HEIGHT_REAL_M)

# ---------------------------------------------------------------------------
# 토픽 계약 — 실물과 시뮬이 동일하게 노출한다 (명세서 1장: 동일 방식 처리)
# 차량별 네임스페이스 아래에 놓인다: /fk01/cmd_vel, /sim01/cmd_vel ...
# ---------------------------------------------------------------------------
TOPIC_CMD_VEL = "cmd_vel"        # geometry_msgs/Twist        (sub)
TOPIC_ODOM = "odom"              # nav_msgs/Odometry          (pub)
TOPIC_SCAN = "scan"              # sensor_msgs/LaserScan      (pub)
TOPIC_FORK_CMD = "fork/cmd"      # std_msgs/Float64  목표 높이(m, 실물 기준)  (sub)
TOPIC_FORK_STATE = "fork/state"  # std_msgs/Float64  실측 높이(m, 실물 기준)  (pub)

# 프레임 ID
FRAME_BASE = "base_link"
FRAME_ODOM = "odom"
FRAME_LIDAR = "laser_frame"

# ---------------------------------------------------------------------------
# 시뮬 지게차(ForkliftB) 기하 — USD 에서 실측한 값
# ---------------------------------------------------------------------------
# 조향륜은 차체 x=+0.578, 비조향 롤러축은 x=-0.910 에 있다.
WHEEL_BASE_M = 1.49       # 조향륜 <-> 비조향축 거리
DRIVE_WHEEL_RADIUS_M = 0.16

# 관절 이름 — ROS2 JointState 의 name 필드에 이 이름을 그대로 쓴다.
JOINT_STEER = "back_wheel_swivel"
JOINT_DRIVE = "back_wheel_drive"
JOINT_LIFT = "lift_joint"

# 시뮬 내부에서 주고받는 관절 지령 토픽 (Nav2 가 쓰는 표준 토픽이 아님)
TOPIC_JOINT_COMMAND = "joint_command"
TOPIC_JOINT_STATES = "joint_states"

# ---------------------------------------------------------------------------
# USD 프림 경로 (ForkliftB 기준)
# ---------------------------------------------------------------------------
PRIM_ROOT = "/World/Forklift"
PRIM_STEER_JOINT = PRIM_ROOT + "/back_wheel_joints/back_wheel_swivel"
PRIM_DRIVE_JOINT = PRIM_ROOT + "/back_wheel_joints/back_wheel_drive"
PRIM_LIFT_JOINT = PRIM_ROOT + "/lift_joint"
PRIM_LIDAR = PRIM_ROOT + "/body/sensors/front_2d_lidar"

# 명세서상 비전 추론은 실물 Jetson 온디바이스(FR-101)이므로 시뮬에서는 불필요.
# RTX 4050 6GB에서 다중 차량(FR-501/502)을 띄우려면 렌더 비용을 줄여야 한다.
PRIMS_TO_DISABLE = [
    PRIM_ROOT + "/body/sensors/hawk_front",
    PRIM_ROOT + "/body/sensors/hawk_left",
    PRIM_ROOT + "/body/sensors/hawk_right",
]
