# ROS_DOMAIN_ID 분리 — 실물 34, 시뮬 0

> 2026-08-04. 관련 이슈: S15P11A304-152

## 왜 나눴나

ROS2 는 **같은 네트워크·같은 도메인이면 노드와 토픽을 자동으로 공유한다**(DDS 디스커버리).
설정이 없으면 전부 도메인 `0` 이다.

2026-08-04 젯슨에서 Nav2 를 띄우다 발견했다 — 팀원 PC 의 Isaac Sim 이 같은 도메인에
있어서 **젯슨에서 그 노드들이 그대로 보였다.**

```
/isaac_clock_pub   /kinematic_fleet   /demo_solo   /fleet_obstacles
/sim_f02/bt_navigator   /sim_f02/controller_server   ...
/sim_f03/...
```

### 🔴 실제로 위험했다

```
/cmd_vel   Subscription count: 2
           Node name: kinematic_fleet      ← Isaac Sim 차량 노드
```

**우리가 `/cmd_vel` 을 발행하면 남의 시뮬 지게차가 움직인다.** 포크 정렬 노드를
`--dry-run` 없이 돌렸으면 그렇게 됐을 것이다. 반대 방향도 성립한다 — 시뮬이 쏜
명령을 우리 모터 브리지가 받는다.

부수 피해도 있었다:

* `rf2o_laser_odometry` 가 **두 개** (이름 충돌)
* 우리 `bt_navigator` 활성화가 3번 만에 됐다 (도메인 분리 후에는 **1번에** 된다)
* `ros2 action list` 에 `/sim_f02/navigate_to_pose` 만 보여, 우리 Nav2 가 안 뜬 것처럼
  보였다

## 값

|대상|ROS_DOMAIN_ID|
|---|---|
|**실물 지게차 (젯슨)**|**34**|
|Isaac Sim / 시뮬|0 (기본값 그대로)|

`34` 는 프로젝트 `A304` 에서 땄다. 기본값 0 을 피한 이유는 시뮬뿐 아니라
**SSAFY 다른 팀과의 충돌**도 막기 위해서다.

## 젯슨 설정 (완료됨)

`~/.bashrc` 와 `~/.profile` **양쪽에** 넣었다.

```bash
export ROS_DOMAIN_ID=34
```

⚠️ **양쪽에 넣어야 한다.** 우분투 `~/.bashrc` 는 맨 앞에서 비대화 셸이면 즉시
`return` 하므로, `ssh orin "..."` 이나 `bash -lc` 로는 적용되지 않는다. 실제로
`.bashrc` 에만 넣고 확인했을 때 값이 비어 있었다.

확인:

```bash
ssh orin 'bash -lc "echo \$ROS_DOMAIN_ID"'      # 34
```

## ⚠️ 붙는 쪽도 같은 도메인이어야 한다

젯슨의 ROS 노드와 **대화하려는 모든 것**이 34 여야 한다.

* **ROS2 MQTT 브리지**(`fast_mqtt_bridge`) — 젯슨에서 돌면 자동으로 34 를 상속한다.
  다른 PC 에서 돌린다면 그쪽도 34 로 맞춰야 백엔드 MOVE 가 전달된다.
* **RViz·`ros2 topic echo` 로 디버깅할 때** — 노트북에서 젯슨 토픽이 안 보이면
  이것부터 의심할 것. `export ROS_DOMAIN_ID=34` 후 다시.
* ⚠️ **systemd 서비스는 `.profile` 을 안 읽는다.** 브리지를 systemd 로 올린다면
  유닛에 `Environment=ROS_DOMAIN_ID=34` 를 명시해야 한다.

## 영향 없는 것

* `fast-onboard-infer`(스테이션 추론 서버) — HTTP 라 ROS 와 무관하다
* 스테이션 측정 경로 전체 — REST·MQTT 이고 ROS 를 안 쓴다

## 검증 (2026-08-04)

|항목|분리 전|분리 후|
|---|---|---|
|`ros2 node list` 에 시뮬 노드|다수|**없음**|
|`/cmd_vel` 구독자|**2** (kinematic_fleet)|**0**|
|`bt_navigator` 활성화|3회 시도|**1회**|
|BT 트리|확인 실패(서비스 타임아웃)|`navigate_to_pose_no_spin.xml` ✅|
