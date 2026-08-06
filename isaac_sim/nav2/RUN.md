# F.A.S.T. 시뮬 Nav2 실행 절차

Isaac Sim → 차량 노드 → Nav2 → RViz 목표 지정까지의 실행 순서.
터미널 3개를 쓴다. **순서가 중요하다.**

---

## 0. 이전 실행 잔여물 정리

```bash
pkill -9 rviz2 2>/dev/null
pkill -9 -f 'controller_server|planner_server|bt_navigator|behavior_server|smoother_server|velocity_smoother|waypoint_follower|map_server|lifecycle_manager|static_transform_publisher' 2>/dev/null
```

---

## 1. [터미널 1] Isaac Sim 실행

⚠️ **ROS 를 source 하지 않은 깨끗한 새 터미널**에서 실행할 것.
시스템 ROS(python3.10)를 source 하면 rclpy 로딩이 실패한다.

```bash
cd /home/ubuntu/forklift_ws
./nav2/scripts/run_isaac_gui.sh
```

- 기동 로그에 **`rclpy loaded`** 가 떠야 한다. 안 뜨면 4단계에서 rclpy 에러가 난다.

GUI 가 뜨면:
1. `ff_nophysics.usd` 열기 (물리 끈 버전. 없으면 `ff.usd` + 2단계 물리 끄기)
2. 지게차 `/World/Forklift_SIM_F02` 가 `(3, 2)` 에 있는지 확인
3. ▶ **Play** (스크립트가 매 프레임 콜백으로 도므로 Play 필수)

---

## 2. [Script Editor] 물리 끄기 (ff_nophysics.usd 면 생략)

`Window > Script Editor`, 입력칸을 비우고 한 번만 붙여넣어 실행:

```python
import omni.usd
from pxr import UsdPhysics, PhysxSchema

stage = omni.usd.get_context().get_stage()
ROOT = "/World/Forklift_SIM_F02"

n_kin = n_col = 0
for p in stage.Traverse():
    if not str(p.GetPath()).startswith(ROOT):
        continue
    # RigidBody -> kinematic (물리에 안 밀리고 코드가 위치 제어)
    if p.HasAPI(UsdPhysics.RigidBodyAPI) or p.HasAPI(PhysxSchema.PhysxRigidBodyAPI):
        rb = UsdPhysics.RigidBodyAPI.Apply(p)
        rb.CreateKinematicEnabledAttr(True)
        n_kin += 1
    for attr in p.GetAttributes():
        if "collisionEnabled" in attr.GetName():
            attr.Set(False)
            n_col += 1

print(f"kinematic 전환: {n_kin}개, 충돌 비활성: {n_col}개")
```

- "cannot create a joint between static bodies" 경고는 **정상**(kinematic 전환됐다는 뜻). 무시.
- 잘 되면 `File > Save As` → `ff_nophysics.usd` 로 저장하면 다음부터 이 단계 생략.

---

## 3. [Script Editor] 차량 노드 실행

입력칸을 비우고 한 줄:

```python
exec(open('/home/ubuntu/forklift_ws/nav2/scripts/kinematic_vehicle.py').read())
```

- **`spawned SIM_F02 at (3.0, 2.0, 0.0)`** 가 뜨면 성공.
- 재시작하려면: `fleet.stop()` 후 위 줄 다시 실행 (지게차가 (3,2)로 리셋됨).

### 위치만 리셋 (Nav2 재시작 없이)
```python
v = fleet.vehicles['SIM_F02']
v.x, v.y, v.yaw = 3.0, 2.0, 0.0
```

---

## 4. [터미널 2] 토픽 확인

```bash
source /opt/ros/humble/setup.bash
ros2 topic list | grep -E "scan|odom|tf|clock"
```

다음 5개가 모두 나와야 한다:
```
/clock
/sim_f02/odom
/sim_f02/scan
/tf
/tf_static
```
없으면 여기서 멈추고 Isaac/스크립트부터 확인. (Play 눌렀는지, 라이다 그래프 있는지)

---

## 5. [터미널 2] Nav2 실행

```bash
cd /home/ubuntu/forklift_ws
./nav2/scripts/run_nav2_static.sh
```

- **`Managed nodes are active`** 가 뜨면 준비 완료.
- 이 터미널은 Nav2 가 계속 점유하므로 그대로 둔다 (Ctrl+C 로 종료).

---

## 6. [터미널 3] RViz + 목표 지정

```bash
source /opt/ros/humble/setup.bash
ros2 run rviz2 rviz2 -d /opt/ros/humble/share/nav2_bringup/rviz/nav2_default_view.rviz
```

RViz 에서:
1. 좌측 `Global Options > Fixed Frame` = **`map`**
2. 상단 툴바 **`2D Goal Pose`** 클릭
3. 맵 **빈 통로**에 **클릭 + 드래그**(끄는 방향 = 도착 시 지게차 앞 방향)

### 명령어로 목표 지정 (RViz 없이)
```bash
ros2 action send_goal /navigate_to_pose nav2_msgs/action/NavigateToPose \
  "{pose: {header: {frame_id: map}, pose: {position: {x: 6.0, y: 20.0}, orientation: {z: 0.0, w: 1.0}}}}"
```

도착 방향 quaternion (z, w):
| 방향 | yaw | z | w |
|---|---|---|---|
| +X (오른쪽) | 0° | 0 | 1 |
| +Y (위) | 90° | 0.707 | 0.707 |
| -X (왼쪽) | 180° | 1 | 0 |
| -Y (아래) | -90° | -0.707 | 0.707 |

⚠️ 후륜조향이라 정확한 방향 도착은 어렵다 (제자리 회전 불가).
`yaw_goal_tolerance: 0.25`(±14°) 안에 들면 도착 처리. 목표는 벽에서 떨어진
빈 통로에 찍을 것 — collision_monitor(정지반경 0.6m)가 벽 근처에서 멈춘다.

---

## 순서 요약

```
0. pkill (정리)
1. [T1] run_isaac_gui.sh → rclpy loaded → ff 열기 → Play
2. [Script] 물리 끄기 (ff_nophysics 면 생략)
3. [Script] kinematic_vehicle.py → spawned 확인
4. [T2] 토픽 5개 확인
5. [T2] run_nav2_static.sh → active 확인
6. [T3] RViz → 2D Goal Pose
```

## 단계별 실패 원인

| 단계 | 실패 증상 | 원인 |
|---|---|---|
| 1 | `rclpy loaded` 안 뜸 | ROS source된 터미널에서 켬 → 깨끗한 터미널 |
| 3 | rclpy import 에러 | 위와 동일 |
| 4 | `/sim_f02/scan` 없음 | Play 안 함 / 라이다 그래프 없음 |
| 5 | `active` 안 뜸 | 토픽·TF 문제 |
| 5 | 플러그인 does not exist | yaml plugin 이름 `::` → `/` |
| 6 | RViz 화면 빔 | Fixed Frame ≠ map |
| 6 | 벽 근처서 멈춤 | 정상 (collision_monitor). 목표를 통로 중앙에 |
| 6 | RViz/Isaac 위치 어긋남 | 물리 kinematic 전환 안 됨 (2단계) |

## 관련 문서

- `README.md` — 좌표·시작위치·차량 제원
- 맵: `maps/sim_warehouse.{pgm,yaml,png}`
- Nav2 설정: `config/nav2_sim.yaml`
