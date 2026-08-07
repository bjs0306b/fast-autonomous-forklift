# Isaac Sim 연동 인수인계

실물 지게차(1/10 미니어처) 쪽 센서 칼리브레이션이 2026-08-06 에 전 단계를 통과했다.
이 문서는 **그 결과를 Isaac Sim 트윈과 붙이려는 사람이 알아야 할 것**을 모은 것이다.

읽는 순서: §1 로 어느 규격이 살아 있는지 파악하고, §2 의 충돌 3건을 먼저 합의한다.
**§2 가 정해지기 전에는 연동 코드를 써도 버려야 한다.**

---

## 0. 지금 상태 요약

| | 상태 |
|---|---|
| 실물 센서 칼리브레이션 | ✅ A~J 전 단계 통과 (`센서-측정-칼리브레이션.md` §3.5) |
| 실물 Nav2 로컬 costmap | ✅ ToF 가 라이다 사각의 낮은 상자를 마킹, 회전해도 유지 |
| 실물 → MQTT 텔레메트리 | ❌ **두 경로 모두 죽어 있음** (§6-1) |
| 시뮬 Nav2 단독 주행 | ✅ 팀원 PC 에서 동작 (`isaac_sim/nav2/RUN.md`) |
| 시뮬 ↔ 실물 좌표 왕복 | ❌ 미검증 |
| 규격 합의 | ❌ 충돌 3건 미해결 (§2) |

---

## 1. 규격 문서 지도 — 어느 것이 살아 있나

프로젝트에 규격 문서가 네 벌 있고 **서로 다른 것을 말한다.** 코드가 실제로 무엇을
하는지 확인해 판정했다.

| 문서 | 상태 | 판정 근거 |
|---|---|---|
| `docs/backend-message/communication-protocol.md` | **살아 있음** | `mqtt_bridge_node.py` 가 여기 적힌 `forklift/{id}/...` 토픽을 그대로 씀 |
| `ros2_ws/orin-pose-spec.md` | **좌표 계약만 유효** | `orin_telemetry_model.py` 의 `SCALE = 10.0` 이 이 문서를 따름. 단 토픽은 다름 |
| `isaac_sim/docs/interface-spec.md` | **초안 v0.1** | 문서 §9 에 미합의 9건이 ⚠️ 로 남아 있음. 좋은 설계 근거지만 계약으로 쓰면 안 됨 |
| `isaac_sim/nav2/README.md`, `RUN.md` | **시뮬 실행 절차로 유효** | 단 경로가 전부 `/home/ubuntu/forklift_ws` — 이 Orin 에선 그대로 못 씀 |

**요지**: 백엔드와 붙는 계약은 `communication-protocol.md`, 좌표 축척 규칙은
`orin-pose-spec.md`, 시뮬 기동 절차는 `isaac_sim/nav2/RUN.md`. `interface-spec.md`
는 설계 의도를 이해할 때만 읽는다.

---

## 2. 규격 충돌 3건 — 이것부터 합의한다

셋 다 **증상이 "조금 어긋난다"로만 나타나** 추적이 오래 걸린다.

### 2.1 MQTT 토픽이 두 가족이다

| 노드 | 토픽 | 대응 문서 |
|---|---|---|
| `mqtt_bridge_node.py` | `forklift/{id}/location` · `/status` · `/path` · `/command` | `communication-protocol.md` |
| `orin_telemetry.py` | `fast/v1/vehicle/fk01/telemetry` | `interface-spec.md` |

같은 워크스페이스에 두 벌이 공존한다. 백엔드는 `forklift/...` 만 구독한다.

**권장: `forklift/...` 로 통일한다.** 백엔드가 이미 그걸 쓰고, 관제 화면·DB·
WebSocket 경로가 전부 거기에 붙어 있다. `fast/v1/...` 은 더 깔끔한 설계지만 지금
바꾸면 백엔드까지 따라 고쳐야 한다.

**결정 필요: E(백엔드), F(트윈)**

### 2.2 각도 단위가 두 벌이다

| 출처 | 필드 | 단위 |
|---|---|---|
| `communication-protocol.md` | `heading` | **degree, [0, 360)** |
| `orin-pose-spec.md` | `pose.yaw` | **radian, −π ~ +π** |
| `orin_telemetry.py` 실제 발행 | `pose.yaw` | radian (`normalize_angle`) |

필드명도 단위도 범위도 다르다. 90° 를 1.5708 로 읽거나 그 반대가 되면 차량이
엉뚱한 방향을 본다.

**결정 필요: E, F**

### 2.3 `base_link` 기준점이 두 벌이다 — 가장 위험

| 출처 | 정의 |
|---|---|
| `ros2_ws/README.md:76` | `base_link` 는 **전륜 구동축** 중심 |
| `docs/센서-측정-칼리브레이션.md:39` | `base_link` = **전륜 구동축** 중심 (REP-103) |
| `ros2_ws/orin-pose-spec.md:80` | 기준점 = `base_link` — **뒷바퀴 축** 중심 |

실물 코드와 모든 static TF 는 **전륜 구동축** 기준으로 잡혀 있다. 트윈을
`orin-pose-spec.md` 대로 뒷바퀴 기준으로 만들면 **축거 0.144 m 만큼 어긋나고**,
회전할 때마다 어긋남의 방향이 바뀌어 "가끔 밀린다"로 보인다.

**`orin-pose-spec.md` 쪽이 오기로 보인다.** 다만 그 문서를 근거로 이미 만든 것이
있는지 확인이 필요하다.

**결정 필요: C(임베디드), D(자율주행), F(트윈)**

---

## 3. 실물 쪽 확정 사실

**값은 설정 파일이 소유한다.** 여기서는 어디를 봐야 하는지와 왜 그런지만 적는다.
숫자를 옮겨 적으면 두 번째 진실 원천이 생겨 곧 어긋난다.

| 알아야 할 것 | 어디에 |
|---|---|
| 좌표 규약 (`base_link` = 전륜축, 바닥에서 30 mm 위) | `docs/센서-측정-칼리브레이션.md` §1 |
| 실측 치수·footprint·축거 | `ros2_ws/README.md` "실측 좌표계" |
| 센서 static TF (IMU·ToF 좌우) | `launch/sensor_usb.launch.py` |
| LiDAR TF | `launch/lidar_odometry.launch.py` `LIDAR_TRANSLATION`/`LIDAR_ROTATION` |
| ToF 기하·피치·필터 | `config/sensors.yaml` |
| 자이로 잡음·엔코더 스케일 | `config/sensors.yaml` |
| 운전값 (조향 범위·속도 한계) | `config/teleop.yaml` |
| 안전 봉투 (재플래시 필요) | `firmware/.../main/config.h` |

### 시뮬이 특히 알아야 할 것 넷

**1. 라이다가 90° 돌아 달려 있다.** `LIDAR_ROTATION` 의 yaw 가 `1.5708` 인 이유다.
이걸 0 으로 두면 rf2o 가 **전진할 때 옆으로 미끄러진다고 계산**한다. 시뮬에서
라이다를 배치할 때 같은 실수를 하지 않도록 알아둘 것.

**2. 후륜 조향이라 제자리 회전이 불가능하다.** Nav2 기본 트리의 `Spin` 이 영원히
끝나지 않으므로 `navigate_to_pose_no_spin.xml` 을 쓴다
(`launch/nav2_forklift.launch.py` 상단 주석). 시뮬 차량도 같은 성질이지만 원인이
다르다 — 실물은 데드밴드, 시뮬은 자전거 모델 적분. **저속 구간 거동이 서로 다르다.**

**3. 두 ToF 는 좌우 측면이 아니라 둘 다 정면이다.** 포크 갭 양쪽에서 앞을 본다.
벌리지 않은 이유는 정면 근거리 사각 때문이며 계산이
`센서-측정-칼리브레이션.md` §2.2 에 있다.

**4. 안전 봉투와 운전값은 다른 것이다.** `config.h` 의 한계는 "여기를 넘으면 기구가
상한다", `teleop.yaml` 은 "실제로 쓰는 범위". 튜닝은 항상 후자에서 한다.

---

## 4. 실물 ↔ 시뮬 차이 대조표

**모든 차이를 없앨 필요는 없다.** 무엇을 맞춰야 하고 무엇은 달라도 되는지가 핵심이다.

| 항목 | 실물 | 시뮬 | 맞춰야 하나 |
|---|---|---|---|
| 프레임 | `base_link`, `odom`, `laser_frame` | `SIM_F02_base_link` 등 | **아니오** — 둘은 MQTT 경계에서만 만난다 |
| 토픽 | `/scan`, `/cmd_vel`, `/odometry/filtered` | `/sim_f02/scan`, `/sim_f02/odom` | **아니오** |
| 축척 | 1/10 (2×3 m) | 10× (20×30 m) | **아니오 — 의도된 차이.** 변환은 Orin 발행 지점 한 곳 |
| `use_sim_time` | 하드코딩 `false` | `true` | **섞지 말 것** (아래) |
| `/scan` QoS | Best Effort | — | **예** — Reliable 로 구독하면 한 건도 못 받는다 |
| 포크 명령 | `/fork/command` String + 호밍 인터록 | `fork_cmd` Float32(m) | **예 — 호환 불가, 합의 필요** |
| `odom→base_link` | EKF 단독 | 시뮬이 직접 씀 | **이중 발행 금지** |
| Nav2 파라미터 | `ros2_ws/nav2_params.yaml` | `isaac_sim/nav2/config/nav2_sim.yaml` | **아니오** — 축척이 달라 별도 유지가 맞다 |
| costmap 관측원 | `scan tof_left tof_right` | `scan` 만 | 시뮬에 ToF 를 넣을지는 선택 (§6) |

### `use_sim_time` 함정

실물 쪽은 **16 곳에 `false` 가 하드코딩**돼 있다 (`ekf.yaml` 1곳,
`nav2_params.yaml` 15곳). 그리고 `nav2_forklift.launch.py` 의 `RewrittenYaml` 은
`default_nav_to_pose_bt_xml` 만 덮어쓰므로, **`use_sim_time:=true` 를 줘도 params
파일이 도로 `False` 로 되돌린다.**

게다가 실물 노드 대부분은 `time.monotonic()` 으로 시간을 잰다. 워치독과 신선도
판정은 `use_sim_time` 과 무관하게 벽시계로 돈다.

**시뮬용으로 Nav2 를 띄울 때는 `nav2_sim.yaml` 을 쓴다.** 실물 params 에
`use_sim_time:=true` 를 얹어 쓰려는 시도는 조용히 실패한다.

---

## 5. 함정 — 실제로 겪은 것만

### 5.1 ROS_DOMAIN_ID 를 먼저 확인한다

**실물 100 / 시뮬 0.** 같은 네트워크에서 도메인이 같으면 ROS2 는 노드를 자동으로
공유한다.

> 2026-08-04 에 실제로 겪었다 — 팀원 PC 의 Isaac Sim 노드가 젯슨에서 그대로 보였고,
> `/cmd_vel` 구독자가 2개가 됐다. **시뮬 차량이 실물 명령을 받고, 실물이 시뮬 명령을
> 받는다.** (`docs/deploy/ros-domain.md`)

```bash
echo $ROS_DOMAIN_ID
ros2 topic info /cmd_vel -v    # 내 노드 말고 다른 게 붙어 있는지
```

**연동 작업의 첫 단계는 항상 이것이다.** 이걸 건너뛰면 이후 모든 관측이 오염된다.

### 5.2 시뮬 자산에 빠진 것이 있다

- **RTX 라이다 ActionGraph 가 레포에 없다.** `RUN.md` 는 GUI 에서 손으로 만들라고만
  한다. 시뮬을 다른 PC 에서 재현할 수 없게 만드는 가장 큰 공백이다
- `ff.usd` / `ff_nophysics.usd` 도 레포에 없다 (`/home/ubuntu/forklift_ws` 에만 있음)
- `isaac_sim/nav2/` 의 경로가 전부 `/home/ubuntu/forklift_ws` 하드코딩
- `isaac_sim/config/nav2_sim.yaml` 은 **오래된 초안**이다. 현재 것은
  `isaac_sim/nav2/config/nav2_sim.yaml`. 헷갈리기 쉽다

### 5.3 파이썬 버전이 다르다

Isaac 은 python3.11, 시스템 ROS 는 3.10 이다. ROS 를 source 한 터미널에서 Isaac 을
켜면 `No module named 'rclpy._rclpy_pybind11'` 이 난다. `run_isaac_gui.sh` 가
`PYTHONPATH` 등을 지우는 이유다. **Isaac 터미널과 ROS 터미널을 분리한다.**

### 5.4 알려진 버그

`isaac_sim/scripts/twin_bridge.py` 의 `SIM_ID` 가 하이픈/언더스코어 불일치라
시뮬 메시지가 조용히 폐기된다 (`PROJECT_IMPLEMENTATION_AUDIT.md` §9-2).

---

## 6. 남은 작업

### 6.1 즉시 — 데모 전 필수

| # | 작업 | 소유 | 근거 |
|---|---|---|---|
| 1 | `nav2_params.yaml` 의 `odom_topic` 을 `/odometry/filtered` 로 (47·392행) | D | `/odom` 은 **발행자가 없다** (실행 중 확인). 컨트롤러가 속도 피드백을 못 받는다 |
| 2 | 실물 텔레메트리 경로를 살린다 | D | **두 경로 다 죽어 있다** (아래) |
| 3 | 각도 단위 합의 | E, F | §2.2 |
| 4 | `base_link` 기준점 합의 | C, D, F | §2.3 |
| 5 | MQTT 토픽 가족 확정 | E, F | §2.1 |

**#2 가 왜 급한가.** 텔레메트리가 안 나가면 트윈에 실물이 안 보인다. 두 경로 모두
막혀 있다.

- `mqtt_bridge` 는 런치되지만 `config/mqtt_bridge.yaml` 의 `location_topic`·
  `status_topic`·`path_topic` 이 **전부 빈 문자열**이라 구독을 아예 만들지 않는다
- `orin_telemetry` 는 스케일 변환(×10)까지 올바르게 구현돼 있지만 **어떤 런치
  파일도 이 노드를 띄우지 않는다.** 게다가 기본 `odom_topic` 이 `/odom` 이라
  띄워도 데이터가 없다

둘 중 하나를 골라 토픽을 `/odometry/filtered` 로 채우면 된다. §2.1 결정에 따른다.

### 6.2 연동 본작업

| # | 작업 | 소유 |
|---|---|---|
| 6 | RTX 라이다 ActionGraph 를 스크립트로 만들어 커밋 | F |
| 7 | `isaac_sim/nav2/` 경로를 상대경로나 환경변수로 | F |
| 8 | 포크 인터페이스 통일 (String+인터록 vs Float32) | C, F |
| 9 | 실물 → 브로커 → 트윈 좌표 왕복 검증 | D, E, F |
| 10 | `isaac_sim/config/nav2_sim.yaml` (구 초안) 삭제 또는 명시적 표시 | F |

### 6.3 선택 — 여유가 있으면

- **시뮬에 ToF 2개 추가.** 현재 `nav2_sim.yaml` 은 `scan` 만 본다. 실물은
  `scan tof_left tof_right` 를 `VoxelLayer` 에 넣는다. 낮은 장애물 회피를 시뮬에서
  검증하려면 필요하다
- FR-403 사전 시뮬레이션, FR-505 대수 산출

---

## 7. 연동 검증 절차

**순서대로. 각 단계를 통과한 뒤 다음으로 간다.**

### 단계 1 — 도메인 분리

```bash
# 실물(젯슨)
echo $ROS_DOMAIN_ID            # 100 이어야 한다
ros2 node list                 # 시뮬 노드가 보이면 실패

# 시뮬 PC
echo $ROS_DOMAIN_ID            # 0 또는 비어 있음
```

**합격**: 양쪽에서 서로의 노드가 보이지 않는다.

이걸 건너뛰면 이후 관측이 전부 오염된다. `/cmd_vel` 구독자가 2개가 되는 사고가
실제로 있었다.

### 단계 2 — 각자 단독 동작

| | 확인 | 합격 |
|---|---|---|
| 실물 | `ros2 topic hz /odometry/filtered` | 30 Hz 부근 |
| 실물 | `ros2 topic hz /scan` | 10 Hz 부근 |
| 시뮬 | `RUN.md` §4 의 토픽 5개 | 전부 존재 |

### 단계 3 — MQTT 왕복

브로커에 붙어 실물 텔레메트리가 올라오는지 본다.

```bash
mosquitto_sub -h <broker> -t 'forklift/+/location' -v
```

**합격**: 실물을 손으로 밀 때 좌표가 따라 움직인다.

**좌표를 반드시 검산한다.** 실물에서 잰 m 값 × 10 이 MQTT 값과 같아야 한다.
세트장 (1.55, 0.40) m → `{"x": 15.5, "y": 4.0}`.

### 단계 4 — 트윈 반영

관제 화면 또는 Isaac 에서 실물 차량이 제 위치에 보이는지.

**합격**: 실물을 세트장 한쪽 끝에서 반대쪽으로 옮기면 트윈에서도 같은 비율로 이동.

**여기서 어긋나면** §2 의 충돌 셋을 먼저 의심한다. 각도가 90° 단위로 틀리면 단위
문제(§2.2), 항상 일정하게 밀리면 기준점 문제(§2.3)다.

### 단계 5 — 명령 왕복

관제에서 목표를 주고 실물이 가는지. `command` → `command-result` 가 돌아오는지.

**`communication-protocol.md` 의 이 대목은 오래됐다.** 그 문서는 이동 adapter 가
`UnavailableCommandAdapter` 라 실제로 움직이지 않는다고 적었지만, 이후
`Nav2CommandAdapter` 가 들어와 기본으로 활성이다 (S15P11A304-192).

다만 **거절 모드로 떨어지는 경로가 둘 남아 있다.**

| 조건 | 결과 |
|---|---|
| `nav2_enabled: false` | MOVE 전부 `REJECTED` |
| `nav2_msgs` import 실패 | MOVE 전부 `REJECTED` |

둘 다 기동 로그에 시끄럽게 남는다. **`Nav2 연동 활성` 이 로그에 있는지 반드시
확인한다.** 예전에 거절 모드가 기본값이던 시절, 브리지는 정상으로 보이는데 MOVE 만
전부 실패했고 그 사실이 아무 데도 안 찍혀 엉뚱한 곳을 팠던 사고가 있었다.

---

## 8. 관련 문서

| 문서 | 언제 보나 |
|---|---|
| `docs/센서-측정-칼리브레이션.md` | 실물 센서 값의 근거와 재측정 절차 |
| `docs/backend-message/communication-protocol.md` | 백엔드와의 계약 (살아 있는 규격) |
| `ros2_ws/orin-pose-spec.md` | 좌표 축척 규칙 |
| `docs/deploy/ros-domain.md` | 도메인 분리 근거와 설정법 |
| `isaac_sim/nav2/RUN.md` | 시뮬 기동 절차 |
| `isaac_sim/docs/interface-spec.md` | 설계 의도 (계약 아님) |
| `docs/ros2/obstacle-avoidance-validation.md` | 장애물 회피 검증 시나리오 |
