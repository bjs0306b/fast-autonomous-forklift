# 처음부터 실행

진입점은 **두 개**다. Isaac 안에서 한 줄, 터미널에서 한 줄.

```
① Isaac Script Editor →  isaac_setup.py     (물리·clock·차량·화물·카메라)
② 터미널             →  run_all.sh          (Nav2·fleet_obstacles·MQTT)
```

---

## 1. Isaac 실행 — **Play 는 아직 누르지 않는다**
ROS 를 source 하지 않은 **새 터미널**에서 (환경 오염 방지):
```bash
cd /home/ubuntu/forklift_ws
./nav2/scripts/run_isaac_gui.sh
```
- 로그에 `rclpy loaded` 확인
- **ff.usd** 열기 (Stop 상태 그대로 둔다)
- ⚠️ 라이다 `minReflectionRangeM=200` 은 건드리지 말 것 (바꾸면 전 측정 -1)

> **왜 Play 를 나중에 누르나**
> 재생 중에 프림을 만들면(F03 복제, 화물 상자) Isaac 이 스테이지를 다시
> 파싱하느라 수십 초 멈춘다("not responding"). 정지 상태에서 만들어 두면
> 그 멈춤이 없다. 데모 중에는 상자의 크기·위치만 바꾸므로 안 멈춘다.

## 2. Isaac Script Editor — 한 줄
```python
exec(open('/home/ubuntu/forklift_ws/nav2/scripts/isaac_setup.py').read())
```
6단계를 순서대로 실행하고 요약을 보여준다.

| 단계 | 내용 |
|---|---|
| 1 물리 | kinematic + collision — 서로 라이다에 잡히되 물리에 안 밀림 |
| 2 clock | `/clock` 발행 — **없으면 Nav2 전체가 얼어붙는다** |
| 3 F02 | 차량 노드 (cmd_vel 구독, odom/TF/state 발행) |
| 4 F03 | 복제 + fleet 등록 |
| 5 화물 | MQTT 로 받은 높이로 파레트+박스 생성 |
| 6 카메라 | MQTT 로 뷰포트 시점 전환 |

## 3. 이제 ▶ Play 를 누른다
프림 생성이 모두 끝난 뒤에 눌러야 멈추지 않는다.

## 4. 터미널 — 한 줄
```bash
/home/ubuntu/forklift_ws/nav2/scripts/run_all.sh --no-mqtt
```
clock 확인 → 좀비 정리 → Nav2 → fleet_obstacles → MQTT 브릿지 순으로 띄운다.
**Ctrl+C 하면 전부 정리된다.**

```bash
./run_all.sh --solo      # 단독 주행 데모용 (F03 을 costmap 에서 뺌)
./run_all.sh --no-mqtt   # MQTT 브릿지 없이
```

로그: `/tmp/nav2.log` · `/tmp/fleet_obstacles.log` · `/tmp/mqtt_bridge.log`

## 4. 점검
```bash
/home/ubuntu/forklift_ws/nav2/scripts/diagnose.sh
```
clock · 차량 위치 · 라이다 · fleet_obstacles · Nav2 설정 · 유령 노드 · inflation 을
한 번에 본다. 1번(clock)과 6번(유령 없음)이 특히 중요하다.

---

# 데모

## A. 맵 크게 한 바퀴 → 입고 바이 (단독) — 동작 확인됨

맵 구조(실측): 가운데 x 9~12 는 랙, 좌우 세로 통로(x 3~8 / x 13~18)가
y 2.5~27.7 전 구간 뚫려 있고 위·아래 홀이 둘을 잇는다. 둘레 약 67 m 순환로.

```
   y=27  (5,27) ◀────── 위 홀 ──────── (15.5,27)
            │                              ▲
            │ 왼쪽 통로       [ 랙 ]        │ 오른쪽 통로
            ▼                              │
   y=4   (5,4) ─────── 아래 홀 ───────▶ (15.5,4) ──▶ 바이(17,5)
```

⚠️ 이 창고는 **주행 가능 공간이 전부 순환로 통로**라 차를 치워 둘 여유가 없다
(순환로에서 최대 3.2 m). 단독 데모에서는 `--solo` 로 F03 을 costmap 에서 빼야
`detected collision ahead` 로 멈추지 않는다.

```bash
./nav2/scripts/run_all.sh --solo
```
```python
# Script Editor — 배치
for vid, sp in [("SIM_F02",(7.0,4.0,0.0)), ("SIM_F03",(2.5,2.0,0.0))]:
    v = fleet.vehicles[vid]
    v.x, v.y, v.yaw = sp; v.v=0.0; v.steer=0.0; v._steps=[]
print("배치 완료")
```
```bash
source /opt/ros/humble/setup.bash
python3 /home/ubuntu/forklift_ws/nav2/scripts/demo_ccw_bay.py
```

## B. 통합 관제 — 작업하면서 순환 교통 규칙 유지

```bash
python3 /home/ubuntu/forklift_ws/nav2/scripts/fleet_manager.py        # 반시계
python3 /home/ubuntu/forklift_ws/nav2/scripts/fleet_manager.py cw     # 시계
```

```
순환 → 스테이션 접근 → 작업(정지) → 순환로 복귀 → 다음 스테이션 …
```

| 규칙 | 내용 |
|---|---|
| 1 | 한 방향 순환만 (관제가 그 방향 목표만 주므로 역주행·추월 불가) |
| 2 | 앞차가 **적재중·대기중이면 9 m 안에서 정지**, 앞차가 움직이면 재개 |
| 3 | 시작할 때 한 대씩 순차 합류 |

앞뒤 판정은 x 좌표가 아니라 **순환로를 따라간 거리(호장)** 로 한다.
세로 구간이 있어 x 만으로는 앞뒤를 알 수 없다.

## C. 그 밖
```bash
python3 .../loop_traffic.py 40     # 순환 + 적재 지점 뒤 대기 (2대)
python3 .../detour.py 16 5         # 앞 차량 우회 (관제가 경유점 지시)
./nav2/scripts/test_cross.sh 15 5  # 단일 크로싱 (계획 경로 y범위 출력)
```

---

# MQTT (docs/interface-spec.md 형식)

```bash
sudo apt install -y mosquitto mosquitto-clients
pip3 install paho-mqtt
```
브릿지는 `run_all.sh` 가 같이 띄운다.

### 보내기
```bash
# 좌표로 이동
mosquitto_pub -t 'fast/v1/vehicle/sim02/task' -m '{"x":15.5,"y":4.0}'

# 픽업 → 드롭오프 (포크 높이 포함)
mosquitto_pub -t 'fast/v1/vehicle/sim02/task' -m '{
  "taskId":"T-0042",
  "pickup":  {"x":10.0,"y":4.0,"yaw":0.0,"forkHeight":0.0},
  "dropoff": {"x":15.5,"y":27.0,"yaw":1.5708,"forkHeight":1.5}}'

# 정지 / 해제 / 전 차량 정지
mosquitto_pub -t 'fast/v1/vehicle/sim02/control' -m '{"command":"ESTOP"}'
mosquitto_pub -t 'fast/v1/vehicle/sim02/control' -m '{"command":"RESUME"}'
mosquitto_pub -t 'fast/v1/control/all'          -m '{"command":"ESTOP"}'

# 측정 높이 -> 파레트+박스 생성 (실물 기준 m)
mosquitto_pub -t 'fast/v1/vehicle/sim02/cargo' -m '{"height":0.15,"cargoId":"C-0007"}'

# Isaac 화면 시점 전환
mosquitto_pub -t 'fast/v1/sim/camera' -m '{"camera":"SIM_F02"}'
mosquitto_pub -t 'fast/v1/sim/camera' -m '{"view":"top"}'
```

### 받기
```bash
mosquitto_sub -t 'fast/v1/vehicle/+/telemetry' -v   # 좌표·속도·포크·적재, 10Hz
mosquitto_sub -t 'fast/v1/vehicle/+/event' -v       # 작업완료/실패, ESTOP
mosquitto_sub -t 'forklift/+/arrived' -v            # 바이 도착 (AI 측정 트리거)
```

화물 생성과 카메라 전환은 USD/뷰포트 접근이 필요해 Isaac 안에서만 가능하다.
그래서 브릿지가 직접 하지 않고 ROS 토픽(`/{ns}/cargo_cmd`, `/sim/camera_cmd`)을
한 번 거친다.

---

# 함정 모음 (전부 실제로 겪은 것)

| 증상 | 원인 | 대응 |
|---|---|---|
| 안 움직이는데 SUCCEEDED | `/clock` 정지 | `ros2 topic hz /clock` → Isaac Play + isaac_setup 재실행 |
| `Goal accepted` 가 안 뜸 / `/plan` 없음 | Stop/Play 로 시간 되감김 | **Nav2 재시작** (run_all.sh 다시) |
| cmd_vel 발행자가 5개 / 가짜 SUCCEEDED | 좀비 Nav2 (여러 번 켬) | run_all.sh 가 자동 정리. 수동이면 pkill 후 한 번만 |
| 상대를 스치고 통과 | fleet_obstacles 미기동 | run_all.sh 가 띄운다 (`--solo` 아닌지 확인) |
| `detected collision ahead` 로 계속 멈춤 | 통로에 다른 차 / costmap 유령 마킹 | `--solo`, 또는 costmap 청소 |
| `Starting point in lethal` | 시작 위치가 벽 속 | 차체가 3.8 m 다. x ≥ 3 이어야 한다 |
| 앞뒤로 꿈틀 / 한 바퀴 돎 | 도착 heading 맞추기 | `yaw_goal_tolerance: 3.14` (적용됨) |
| 라이다 전부 -1 | 파이프라인 꼬임 | Isaac Stop→Play (minReflection 은 금지) |
| Isaac 이 멈춤 | 두 차 겹침(PhysX) / GPU 과부하 | 겹치지 않게, ToF·카메라 끄기 |

### ⚠️ Stop/Play 를 누르면 Nav2 를 반드시 다시 띄운다
타임라인을 Stop 하면 시뮬 시간이 0 으로 되감긴다. 이미 떠 있던 Nav2 는 시간이
거꾸로 간 것을 보고 TF 버퍼·타이머가 꼬여 **목표를 수락조차 하지 않는다.**

---

# 현재 설정값

footprint 3.8×1.9 (실측 bbox) · inflation 0.4 · lookahead 0.6 ·
REEDS_SHEPP · yaw_goal_tolerance 3.14 · min turning radius 0.81 ·
obstacle_layer 소스 = scan + fleet

# 알려진 미해결

- `cargo_demo.py` 의 `RACK_SLOTS` 접근 좌표(A1 `2.26,9.4`, B1 `11.5,10.5`)가
  **맵상 주행 불가**다. 차체를 3.2×1.5 로 잡았을 때 잰 값이라 실측 3.8×1.9 로는
  못 들어간다. 랙 접근 좌표를 다시 잡거나 맵 생성 높이를 조정해야 한다.
  `fleet_manager.py` 는 임시로 검증된 `(5, 20)` 을 RACK 스테이션으로 쓴다.
- 관제 화면(WebSocket)·AI 측정 결과 메시지 형식은 아직 팀 합의 전
  (`docs/interface-spec.md` 5·6절, `docs/mqtt-arrived.md` 확인 필요 항목).
