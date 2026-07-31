# F.A.S.T. 시뮬 Nav2 설정 — 좌표·시작 위치

시뮬 창고와 지게차의 좌표 기준을 한곳에 모은 문서.
**세 곳(스크립트 spawn / Nav2 yaml / Isaac 씬 프림)의 값이 반드시 일치해야 한다.**
하나라도 어긋나면 Nav2 가 시작 위치를 잘못 잡아 목표를 거부한다.

## 축척

| | 크기 | 비고 |
|---|---|---|
| 시뮬 세계 (ff.usd) | 20 × 30 m | 실물의 10배 |
| 실물 목업 | 2 × 3 m | 미니어처 |
| **변환** | 실물 = 시뮬 / 10 | MQTT 경계에서만 적용 |

Nav2·맵·costmap 은 전부 **10배 세계(시뮬 좌표)** 에서 돈다.
실물 연동 시에만 좌표를 1/10 한다.

## 창고

| 항목 | 값 |
|---|---|
| 크기 | 20 m (X) × 30 m (Y) |
| 원점 (0,0) | 창고 왼쪽아래 구석 |
| 랙 줄 L | X 0.4~1.5, Y 8.5~24.5 (왼쪽) |
| 랙 줄 C | X 9.7~10.8, Y 8.5~24.5 (중앙) |
| 통로 | L-C 사이 약 8 m, C-오른쪽벽 약 8.7 m |

## 시작 위치 (SIM_F02)

| 항목 | 값 |
|---|---|
| **위치** | **x = 3.0, y = 2.0** (m, 시뮬 좌표) |
| **방향 (yaw)** | **0 rad** = +X (오른쪽, 창고 안쪽) |
| Z (높이) | 0.0 (씬에서 authored height 유지) |
| 실물 환산 | x = 0.3, y = 0.2 (÷10) |

시작점은 창고 아래쪽 빈 공간. 랙(Y 8.5~)보다 아래라 회전 여유가 충분하다.
+X 로 출발하면 통로를 따라 창고 안쪽으로 진입한다.

### 이 값이 들어가는 세 곳 (반드시 동일)

| 파일 | 항목 | 값 |
|---|---|---|
| `nav2/scripts/kinematic_vehicle.py` | `spawn=(x, y, yaw)` | `(3.0, 2.0, 0.0)` |
| `nav2/config/nav2_sim.yaml` | `amcl.initial_pose` | `x:3.0 y:2.0 yaw:0.0` |
| Isaac 씬 | `/World/Forklift_SIM_F02` Translate | `X:3.0 Y:2.0`, Rotate Z: `0` |

## 프레임 이름 (kinematic_vehicle.py 발행)

| 프레임 | 용도 |
|---|---|
| `SIM_F02_odom` | 오도메트리 원점 |
| `SIM_F02_base_link` | 차체 |
| `SIM_F02_laser` | 라이다 (씬 그래프에서 설정) |
| `map` | 전역 (map → SIM_F02_odom 은 static, run_nav2_static.sh) |

## 토픽 (네임스페이스 소문자: sim_f02)

| 토픽 | 방향 | 발행 주체 |
|---|---|---|
| `/sim_f02/cmd_vel` | 구독 | Nav2 → 차량 |
| `/sim_f02/odom` | 발행 | kinematic_vehicle.py |
| `/sim_f02/scan` | 발행 | **Isaac 씬 그래프 (라이다)** |
| `/tf`, `/tf_static` | 발행 | kinematic_vehicle.py |
| `/clock` | 발행 | Isaac 씬 그래프 |

주의: 차량 ID 는 ROS2 에서 `SIM_F02`(하이픈 불가), 네임스페이스는 소문자 `sim_f02`.

## 파일

```
nav2/
  make_map.py              맵 생성기 (obstacles.txt 읽어 pgm/yaml 생성)
  maps/
    obstacles.txt          라이다 높이(1.2m) 장애물 목록 (편집 가능)
    sim_warehouse.pgm      맵 이미지 (시뮬·실물 공용)
    sim_warehouse.yaml     시뮬용 (20×30m, 0.05 m/px)
    sim_warehouse_real.yaml 실물용 (2×3m, 0.005 m/px) — Orin 에 전달
    sim_warehouse.png       미리보기
  config/
    nav2_sim.yaml          Nav2 파라미터 (Ackermann/후륜조향)
  scripts/
    kinematic_vehicle.py   지게차 구동·odom·TF (Isaac Script Editor 에 붙여넣기)
    run_nav2_static.sh     Nav2 실행 (static map→odom, AMCL 미사용)
```

## 실행 순서

```
1. Isaac Sim: ff.usd 열기 + ForkliftC 를 (3,2,yaw0) 에 배치 + 라이다 그래프
2. Isaac Sim: Play
3. Script Editor: kinematic_vehicle.py 붙여넣고 실행
4. 터미널: ros2 topic list 로 /sim_f02/{odom,scan}, /tf, /clock 확인
5. 터미널: ./nav2/scripts/run_nav2_static.sh
6. RViz: 2D Goal Pose 로 목표 찍기
```

## 차량 운동 제원 (10배 세계)

미니어처 실측(포크 포함 전체 42cm = 차체 32 + 포크 10, 폭 15cm,
축거 14cm, 조향 60°)을 10배 한 값.

| 항목 | 값 | 근거 |
|---|---|---|
| 축거 (wheelbase) | 1.4 m | 14cm × 10, 후륜 조향 |
| 최대 조향각 | 1.047 rad (60°) | 실측 |
| **최소 회전반경** | **0.81 m** | = 1.4 / tan(60°) |
| 차체 크기 (footprint) | 3.2 × 1.5 m | 차체 32×15cm × 10 (포크 제외) |
| inflation_radius | 1.8 m | 외접원 1.77m 이상 |
| 최대 속도 | 2.0 m/s | |
| 포크 행정 | 0 ~ 1.0 m | 포크 10cm × 10 |

⚠️ 후륜 조향이라 **제자리 회전 불가**. Nav2 는 SMAC Hybrid-A* (DUBIN) +
Regulated Pure Pursuit 사용. `minimum_turning_radius: 0.81` 이 통로 폭과
연동되므로 함께 유지할 것.

⚠️ footprint 는 **차체(3.2m)** 기준. 포크(1m)는 제외 — 항상 장애물로 치면
팔레트 아래로 못 들어간다. 시작점 (3,2)는 아래벽까지 2m 로 외접원(1.77m)에
아슬아슬하니, 회전 시 벽에 스치면 (4,4) 로 옮길 것.
