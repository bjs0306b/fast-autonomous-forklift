# 관제 로직 구현 명세 (E팀용)

지금은 F팀의 파이썬(`nav2/scripts/demo_loop2.py`)이 관제 노릇을 하고 있습니다.
이 문서는 그 로직을 백엔드로 옮기기 위한 설계서입니다.

연동 방법(브로커·토픽·페이로드)은 [backend-mqtt-guide.md](backend-mqtt-guide.md),
알고리즘 배경은 [traffic-rules-spec.md](traffic-rules-spec.md) 를 봅니다.

참조 구현
- `nav2/scripts/demo_loop2.py` — 관제 로직 전체
- `nav2/scripts/track.py` — 호장 투영 (60줄, 그대로 번역하면 됩니다)

---

## 0. 무엇을 만드는가

```
        10 Hz telemetry
   차량 ──────────────▶ 백엔드
                          │  ① 위치를 순환로 위 거리(s)로 투영
                          │  ② 차간 거리 판정
                          │  ③ 주기 상태기계 진행
                          ▼
   차량 ◀────────────── task / control / cargo
```

백엔드가 정하는 것은 **네 가지**입니다.

| 결정 | 규칙 |
|---|---|
| 누가 먼저 출발하는가 | 실물(fk01) 최우선, 그다음 한 대씩 |
| 얼마나 떨어져 다니는가 | 앞차와 gap 으로 판정 |
| 어디로 가는가 | 순환로 모서리를 하나씩 |
| 어느 랙에 적재하는가 | 차량별 배정표 |

차량은 순환로가 있다는 것조차 모릅니다. 받은 좌표로 갈 뿐입니다.

---

## 0.5 연결 판정

**차량이 "연결됐다" 고 알리는 메시지는 없습니다.** telemetry 가 오는지로
판단합니다. MQTT 는 연결이 끊겨도 브로커가 알려주지 않기 때문입니다.

```java
boolean isOnline(Vehicle v) {
    return System.currentTimeMillis() - v.lastTs < 3000;   // 3초
}
```

| 상태 | 판정 | 처리 |
|---|---|---|
| 3초 안에 telemetry 수신 | `ONLINE` | 정상 |
| 3초 이상 없음 | `LOST` | **목표를 보내지 않는다.** 화면에 표시 |
| 한 번도 온 적 없음 | `OFFLINE` | 시작 대상에서 제외 |

`LOST` 인 차량에 목표를 계속 보내면, 되살아났을 때 밀린 명령이 한꺼번에
적용되어 엉뚱하게 움직입니다. **끊긴 동안은 아무것도 보내지 마세요.**

### 선택 — LWT 로 즉시 감지

차량이 접속할 때 유언(Last Will)을 걸어두면 끊김을 바로 알 수 있습니다.
지금 규격에는 없고, 필요하면 추가합니다.

```python
# 차량 쪽 (connect 전에 설정)
c.will_set(f"fast/v1/vehicle/{VID}/status",
           '{"online":false}', qos=1, retain=True)
c.connect(...)
c.publish(f"fast/v1/vehicle/{VID}/status",
          '{"online":true}', qos=1, retain=True)
```

타임스탬프 방식만으로도 충분하므로, LWT 는 여유가 있을 때 붙이면 됩니다.

---

## 0.6 운행 제어 — 관제 화면에서 시작·정지

**"시작 시그널" 이라는 별도 명령은 없습니다.** 차량은 스스로 출발하지 않고,
백엔드가 첫 `task` 를 보내야 움직입니다. 즉 **첫 목표를 주는 것이 곧 시작**입니다.

관제 화면(웹)에서는 이런 조작이 필요합니다.

| 조작 | 백엔드가 하는 일 |
|---|---|
| **운행 시작** | 규칙 0 으로 한 대씩 `joined` 를 켜고 첫 `task` 발행 |
| **운행 정지** | 모든 차량에 `HOLD`. `joined` 는 유지 |
| **운행 종료** | 모든 차량에 `HOLD` + `joined` 해제 + 주기 초기화 |
| **차량 개별 정지/재개** | 그 차량에만 `control` |
| **비상 정지** | `fast/v1/control/all` 에 `ESTOP` |

### 프론트 → 백엔드 (REST 예시)

MQTT 는 차량과의 통신용이고, **웹과 백엔드 사이는 REST/WebSocket** 이
자연스럽습니다. 프론트가 MQTT 를 직접 쏘게 하지 마세요 — 규칙 판정이
백엔드에 있는데 프론트가 끼어들면 서로 밀어냅니다.

```
POST /api/operation/start          운행 시작
POST /api/operation/stop           전체 일시정지 (HOLD)
POST /api/operation/estop          비상정지
POST /api/operation/resume         재개
POST /api/operation/reset          종료 + 주기 초기화

POST /api/vehicles/{id}/hold       차량 하나 정지
POST /api/vehicles/{id}/resume     차량 하나 재개

GET  /api/vehicles                 현재 상태 목록 (아래 응답 예)
```

```json
[
  { "id": "fk01",  "kind": "real", "online": true,
    "pose": {"x": 4.0, "y": 2.0, "yaw": 0.0},
    "state": "IDLE", "loaded": false, "cargo": null,
    "phase": "TO_BAY", "target": "BAY", "cycles": 0,
    "joined": false, "held": false },
  { "id": "sim02", "kind": "sim",  "online": true, "...": "..." }
]
```

### 운행 상태

백엔드가 전체 운행 상태를 하나 들고 있으면 화면이 단순해집니다.

```java
enum OperationState { IDLE, RUNNING, PAUSED, ESTOPPED }
```

| 상태 | 뜻 | 화면 |
|---|---|---|
| `IDLE` | 아직 시작 안 함 | [운행 시작] 버튼 활성 |
| `RUNNING` | 주행 중 | [일시정지] [비상정지] |
| `PAUSED` | 전체 HOLD | [재개] [종료] |
| `ESTOPPED` | 비상정지 | [재개] — 확인 후에만 |

### 시작 버튼을 눌렀을 때

```java
void startOperation() {
    if (opState != OperationState.IDLE) return;

    // 온라인 차량이 하나도 없으면 시작하지 않는다
    long ready = vehicles.stream().filter(this::isOnline).count();
    if (ready == 0) throw new IllegalStateException("연결된 차량이 없습니다");

    for (Vehicle v : vehicles) {
        v.joined = false;             // 규칙 0 이 한 대씩 켠다
        v.phase = Phase.TO_BAY;
        v.target = "BAY";
        v.cycles = 0;
        v.rackIndex = 0;
    }
    opState = OperationState.RUNNING;
    // 실제 출발은 다음 tick 의 releaseOne() 이 fk01 부터 순서대로 낸다
}
```

**시작 버튼이 곧바로 세 대를 다 내보내지 않습니다.** `releaseOne()` 이
3초 간격으로 한 대씩 내보내므로, 화면에는 "합류 중 (1/3)" 같은 표시를
해주면 좋습니다.

### 프론트에 실시간으로 밀어줄 것

WebSocket(STOMP)으로 0.5~1 초마다:

```json
{ "opState": "RUNNING",
  "vehicles": [ ... 위 GET 응답과 같은 배열 ... ],
  "racks": { "A1": {"occupied": true, "cargoId": "C-0007"}, "A2": {...} } }
```

telemetry 가 10 Hz 로 오지만 화면은 그렇게 자주 갱신할 필요가 없습니다.
**프론트에서 보간**하면 1 Hz 로 밀어도 부드럽게 보입니다.

```js
// 마지막 두 표본 사이를 60fps 로 보간
const t = (now - prev.ts) / (curr.ts - prev.ts);
x = prev.x + (curr.x - prev.x) * Math.min(t, 1);
```

또는 CSS 한 줄로:

```css
.vehicle { transition: transform 500ms linear; }
```

---

## 1. 좌표와 지형

모든 좌표는 **시뮬 좌표계** (0~20 × 0~30). 백엔드는 변환하지 않습니다.

### 순환로 (반시계, 둘레 67.0)

```
   C2(5, 27) ◀────────── C1(15.5, 27)
      │                        ▲
      ▼ 남                     │ 북
   C3(5, 4) ──────────▶ C0(15.5, 4)
              동
```

| 모서리 | 좌표 |
|---|---|
| C0 | 15.5, 4.0 |
| C1 | 15.5, 27.0 |
| C2 | 5.0, 27.0 |
| C3 | 5.0, 4.0 |

### 스테이션

| 이름 | 좌표 | yaw |
|---|---|---|
| BAY (입고) | 16.5, 5.0 | 0 (동) |
| EXIT (바이 탈출) | 15.5, 4.0 | 1.5708 |
| A랙 접근점 | 5.0, y | 3.1416 (서) |
| B랙 접근점 | 14.5, y | 3.1416 |

슬롯 y 12칸 (A·B 공통):
`9.3 10.5 11.8 13.2 14.5 15.8 17.2 18.5 19.7 21.2 22.5 23.8`

전체 좌표는 `nav2/config/rack_slots.json` 에 있습니다.

---

## 2. 호장(arc-length) 투영 ← 가장 중요

차간 거리를 **유클리드 거리로 재면 안 됩니다.** 왼쪽 통로(x=5)와 오른쪽
통로(x=15.5)는 직선으로 10.5 지만 경로상으로는 반 바퀴(약 33) 떨어져 있습니다.

차량 (x, y) 를 순환로 폴리라인 위로 투영해 **시작점부터의 거리 s** 로 바꿉니다.

```java
public class Track {
    private final double[][] corners;   // 반시계 4개
    private final double[] cumLen;      // 각 모서리까지 누적 길이
    public final double length;         // 67.0

    /** (x,y) 를 가장 가까운 변에 투영해 s 와 이탈거리를 돌려준다. */
    public double[] project(double x, double y) {
        double bestS = 0, bestOff = Double.MAX_VALUE;
        for (int i = 0; i < corners.length; i++) {
            double[] a = corners[i];
            double[] b = corners[(i + 1) % corners.length];
            double dx = b[0] - a[0], dy = b[1] - a[1];
            double seg = Math.hypot(dx, dy);
            double t = ((x - a[0]) * dx + (y - a[1]) * dy) / (seg * seg);
            t = Math.max(0, Math.min(1, t));
            double px = a[0] + t * dx, py = a[1] + t * dy;
            double off = Math.hypot(x - px, y - py);
            if (off < bestOff) {
                bestOff = off;
                bestS = cumLen[i] + t * seg;
            }
        }
        return new double[]{bestS, bestOff};
    }

    /** from 에서 to 까지 진행 방향으로 몇 m 인가 (항상 0 이상). */
    public double gap(double from, double to) {
        double g = (to - from) % length;
        return g < 0 ? g + length : g;
    }
}
```

`gap(내 s, 앞차 s)` 가 작을수록 앞차가 가깝습니다. **뺄셈 방향과 mod 를
반드시 지켜야** 합니다 — 뒤차를 앞차로 착각하면 엉뚱한 차가 멈춥니다.

---

## 3. 차량 상태

```java
class Vehicle {
    String id;                 // "fk01" | "sim02" | "sim03"
    boolean real;              // fk01 이면 true

    // telemetry 에서 갱신
    double x, y, yaw;
    double vLinear;
    String state;              // IDLE MOVING LOADING UNLOADING ...
    boolean loaded;
    long lastTs;

    // 백엔드가 관리
    boolean joined;            // 순환로 합류 허가를 받았는가
    Phase phase;               // 주기 단계
    String target;             // 지금 향하는 스테이션 이름
    int rackIndex;             // 다음에 쓸 랙 번호
    int cycles;
    Integer cornerIdx;         // 목표 모서리
    String lastGoal;           // 중복 전송 방지
    boolean held;              // 규칙으로 멈춰 있는가
    long workUntil;            // 작업 종료 예정 시각
    double[] lastPos;          // 정체 감시
    long lastMoveT;
}

enum Phase { TO_BAY, ALIGN_BAY, LOAD, TO_EXIT, TO_RACK, RACK }
```

---

## 4. 규칙

### 규칙 0 — 합류 순서 (실물 최우선)

아직 `joined` 가 아닌 차량 중 **한 번에 한 대만** 허가합니다.
순서는 **fk01 이 언제나 먼저**입니다.

```java
List<Vehicle> entryOrder() {
    return vehicles.stream()
        .sorted(Comparator.comparingInt((Vehicle v) -> v.real ? 0 : 1)
                          .thenComparing(v -> v.id))
        .toList();
}
```

**왜 실물이 먼저인가.** 실물은 사람이 세트장에서 직접 다뤄야 해서 마음대로
세웠다 다시 보낼 수 없습니다. 먼저 내보내 흐름을 잡게 하고 시뮬이 뒤를
따르는 편이, 나중에 앞차들 사이로 끼어드는 것보다 훨씬 안전합니다.

```java
void releaseOne() {
    if (releaseCooldown > 0) { releaseCooldown -= TICK; return; }

    for (Vehicle v : entryOrder()) {
        if (v.joined) continue;
        if (!isOnline(v)) continue;      // 끊긴 차에 시작을 주지 않는다
        double[] pr = track.project(v.x, v.y);
        double sMe = pr[0];

        // 이미 합류해 움직이는 차가 앞에 가까이 있으면 이번 주기는 보류
        Double gap = null;
        for (Vehicle o : vehicles) {
            if (o == v || !o.joined) continue;
            double[] po = track.project(o.x, o.y);
            if (po[1] > OFF_LOOP_TOL) continue;      // 순환로를 벗어난 차는 제외
            double g = track.gap(sMe, po[0]);
            if (gap == null || g < gap) gap = g;
        }
        if (gap != null && gap < ENTRY_HEADWAY) return;   // 아무도 더 안 올린다

        v.joined = true;
        releaseCooldown = ENTRY_INTERVAL;
        return;                                          // 한 주기에 한 대만
    }
}
```

| 상수 | 값 |
|---|---|
| `ENTRY_HEADWAY` | 10.0 |
| `ENTRY_INTERVAL` | 3.0 초 |
| `OFF_LOOP_TOL` | 3.0 |

### 규칙 1 — 일방통행

목표는 언제나 **진행 방향 다음 모서리**입니다. 역주행 목표를 주지 않습니다.

```java
int nextCorner(double sMe) {
    for (int i = 0; i < 4; i++)
        if (track.gap(sMe, cornerS[i]) > CORNER_TOL) return i;
    return 0;
}
```

### 규칙 2 — 차간 유지

```java
String blocker(Vehicle v, double sMe) {
    for (Vehicle o : vehicles) {
        if (o == v) continue;
        double[] po = track.project(o.x, o.y);
        if (po[1] > OFF_LOOP_TOL) continue;
        double gap = track.gap(sMe, po[0]);

        String kind = stoppedKind(o);        // "작업중" | "대기중" | null
        if (kind != null && gap < HOLD_DIST) return o.id + " " + kind;
        if (gap < SAFE_DIST)                 return o.id + " 근접";
    }
    return null;
}

String stoppedKind(Vehicle o) {
    if ("LOADING".equals(o.state) || "UNLOADING".equals(o.state)) return "작업중";
    if ("HOLDING".equals(o.state) || "ESTOPPED".equals(o.state))  return "관제정지";
    if (o.held)                                                    return "대기중";
    return null;
}
```

| 상수 | 값 | 뜻 |
|---|---|---|
| `HOLD_DIST` | 9.0 | 앞차가 작업/대기 중이면 이 안에서 멈춤 |
| `SAFE_DIST` | 6.0 | 그냥 가까울 때 멈춤 |
| `CORNER_TOL` | 2.0 | 모서리 도달 판정 |
| `ARRIVE_TOL` | 1.5 | 스테이션 도달 판정 |

막혔으면 `control` 로 `HOLD` 를 보내고, 풀리면 목표를 새로 보냅니다.

### 규칙 3 — 바이는 한 대만

입고 바이는 공용입니다. 예약자가 있으면 다른 차는 **멈추지 말고 순환로를
계속 돕니다.**

```java
if ("BAY".equals(v.target) && bayOwner != null && !bayOwner.equals(v.id)) {
    driveLoop(v, sMe);        // 대기 선회 — 멈추지 않는다
    return;
}
```

**차선에 세우면 뒤차가 막혀 교착이 생깁니다.** 이게 이 규칙의 핵심입니다.
바이를 떠날 때(`TO_EXIT` 도착) 반납합니다.

### 규칙 4 — 정체 감시

Nav2 목표가 조용히 실패하면 아무 일도 일어나지 않습니다.

```java
if (dist(v.x, v.y, v.lastPos) > STALL_MOVE) {
    v.lastPos = new double[]{v.x, v.y};
    v.lastMoveT = now();
} else if (now() - v.lastMoveT > STALL_SEC) {
    v.lastGoal = null;          // 같은 목표를 다시 보내게
    v.lastMoveT = now();
}
```

| 상수 | 값 |
|---|---|
| `STALL_SEC` | 20 초 |
| `STALL_MOVE` | 0.3 |

### 규칙 5 — 관제 정지 존중

`control` 로 `HOLD`/`ESTOP` 을 보낸 차량에는 **목표를 보내지 않습니다.**
정체 감시 시각도 계속 밀어 둡니다 — 안 그러면 해제 직후 정체로 오판합니다.

---

## 5. 주기 상태기계

```
TO_BAY ──도착──▶ ALIGN_BAY ──정렬완료──▶ LOAD ──적재확인──▶ TO_EXIT
                                                              │
   ┌──────────────────────────────────────────────────────────┘
   ▼
TO_RACK ──도착──▶ RACK ──적재완료──▶ (주기+1) TO_BAY
```

| 단계 | 무엇을 하나 | 다음으로 넘어가는 조건 |
|---|---|---|
| `TO_BAY` | 순환로를 돌아 바이로 | 바이 반경 1.5 안 |
| `ALIGN_BAY` | `cargo` ← `{"action":"align_bay"}` | 7초 또는 `state != LOADING` |
| `LOAD` | `cargo` ← `{"height":0.15}` | **`loaded == true`** (최대 40초) |
| `TO_EXIT` | EXIT 으로. 바이 반납 | EXIT 반경 1.5 안 |
| `TO_RACK` | 랙 접근점으로 | 접근점 반경 1.5 안 |
| `RACK` | `task` ← `PLACE_RACK` | **`loaded == false`** (최대 90초) |

**시간으로만 넘어가면 안 됩니다.** `loaded` 를 확인하지 않으면 적재에 실패한
차가 빈 포크로 랙까지 가서 아무것도 못 놓고 계속 순환합니다 (실제로 겪은
문제입니다).

---

## 6. 랙 배정

```java
Map<String, List<String>> RACKS = Map.of(
    "fk01",  IntStream.rangeClosed(1, 12).mapToObj(i -> "B" + i).toList(),
    "sim02", IntStream.rangeClosed(1, 12).mapToObj(i -> "A" + i).toList(),
    "sim03", IntStream.rangeClosed(1, 12).mapToObj(i -> "B" + i).toList()
);

String nextRack(Vehicle v) {
    List<String> list = RACKS.get(v.id);
    return list.get(v.rackIndex++ % list.size());
}
```

실물(fk01)에 **B랙(가운데)** 을 주는 이유: 오른쪽 통로가 넓고(860 mm),
세트장에서 사람이 접근하기 쉬워 문제가 생겼을 때 손대기 편합니다.

---

## 7. 발행 시점과 페이로드

| 언제 | 토픽 | 페이로드 |
|---|---|---|
| 다음 모서리/스테이션 | `.../task` | `{"taskId":"T-1","x":15.5,"y":27.0,"yaw":1.5708}` |
| 규칙으로 멈춤 | `.../control` | `{"command":"HOLD"}` |
| 재개 | `.../control` | `{"command":"RESUME"}` |
| 바이 정렬 | `.../cargo` | `{"action":"align_bay"}` |
| 화물 받기 | `.../cargo` | `{"height":0.15,"cargoId":"C-0007"}` |
| 랙 적재 | `.../task` | 아래 |

랙 적재는 **시뮬·실물 공통 페이로드**를 씁니다.

```json
{ "taskId": "T-2", "action": "PLACE_RACK", "rack": "A1", "cargoId": "C-0007",
  "approach":    { "x": 5.00, "y": 9.30, "yaw": 3.1416 },
  "dock":        { "x": 2.60, "y": 9.30, "yaw": 3.1416 },
  "shelfHeight": 1.325,
  "reverseDist": 2.0 }
```

시뮬은 `rack` 이름을, 실물은 좌표를 씁니다. 한 메시지에 둘 다 담으면
양쪽 다 동작합니다.

**같은 목표를 반복해 보내지 마세요.** `lastGoal` 로 중복을 걸러야 차량이
목표를 계속 갈아타며 버벅이지 않습니다.

---

## 8. 제어 주기

```java
@Scheduled(fixedRate = 500)   // 0.5 초
void tick() {
    releaseOne();
    for (Vehicle v : vehicles) {
        if (isLost(v)) continue;              // ts 가 3초 이상 낡음
        if (!v.joined) continue;
        if (v.externallyStopped) continue;    // 규칙 5
        updateStall(v);
        if (isWorking(v.phase)) { advanceWork(v); continue; }
        double[] pr = track.project(v.x, v.y);
        String blk = blocker(v, pr[0]);
        if (blk != null) { hold(v, blk); continue; }
        driveToward(v, pr[0]);
    }
}
```

telemetry 가 10 Hz 로 오므로 판단은 0.5 초면 충분합니다. 차량이 1 m/s 라
그 사이 0.5 이동인데, HOLD 거리 9.0 에 비하면 무시할 수준입니다.

---

## 9. 검증 방법

F팀 데모와 **나란히 돌려 비교**할 수 있습니다.

```
1단계  telemetry 를 받아 관제 화면에 차량을 그린다      (데모 켜둔 채)
2단계  정지 / 재개를 쏴본다                            (데모 켜둔 채 가능)
3단계  목적지 지시를 쏴본다                            (데모 꺼야 함)
4단계  규칙 전체를 켜고 데모를 끈다
```

1·2단계는 데모와 충돌하지 않습니다. 3단계부터는 둘 다 목표를 보내 서로
밀어내므로 F팀에 데모를 꺼 달라고 하세요.

4단계 후 확인할 것:

- [ ] fk01 이 가장 먼저 출발하는가
- [ ] 세 대가 3초 간격으로 합류하는가
- [ ] 앞차가 적재 중일 때 뒤차가 9.0 안에서 멈추는가
- [ ] 바이가 점유됐을 때 다른 차가 **멈추지 않고 계속 도는가**
- [ ] 한 주기가 끝나고 다시 바이로 오는가
- [ ] 20초 정체 시 목표가 재전송되는가

---

## 10. 흔한 함정

| 함정 | 결과 |
|---|---|
| **유클리드 거리로 차간 판정** | 반대편 통로 차를 앞차로 착각해 엉뚱하게 멈춤 |
| gap 뺄셈 방향 반대 | 뒤차를 앞차로 봄 |
| 바이 대기 중 차선에 정지 | 뒤차가 막혀 **교착** |
| 시간만 보고 단계 전환 | 적재 실패한 차가 빈 포크로 순환 |
| 같은 목표 반복 발행 | 차량이 목표를 계속 갈아타며 버벅임 |
| 정지 중인 차에 목표 전송 | 무시되지만 로그가 지저분해짐 |
| 정지 중 정체 감시 방치 | 해제 직후 정체로 오판해 재전송 |

---

## 11. 아직 정하지 못한 것

| 항목 | 내용 |
|---|---|
| `arrived` 접두사 | 혼자 `forklift/` 를 씁니다. `fast/v1/vehicle/{id}/arrived` 로 통일할까요 |
| AI 연동 | `arrived` 를 받아 `cargo` 를 발행하는 주체가 AI 인지 백엔드인지 |
| 실물 `arrived` | C팀 구현 전이면 브릿지가 대신 판정하도록 넣을 수 있습니다 |
| 주기 종료 조건 | 지금은 무한 반복입니다. 재고가 다 차면 어떻게 할지 |
