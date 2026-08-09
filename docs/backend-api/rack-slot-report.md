# 랙 슬롯 좌표·적재 위치 선정 보고서

작성 2026-08-07 · 백엔드(E팀) · 대상 `storage_slot` / `PlacementService`

---

## 1. 요약

F팀 `backend-mqtt-guide`(2026-08-05) §6이 준 랙 좌표를 DB 시드로 옮기고, 그 슬롯 중 하나를
고르는 로직을 정리했다. **코드와 데이터는 준비됐으나 아직 어느 DB에도 적용하지 않았다.**

| 항목 | 상태 |
|---|---|
| 시드 SQL 24칸(A001~B012) | ✅ `src/main/resources/db/seed-rack-slots.sql` |
| 선정 로직(높이·폭) | ✅ `PlacementService` · 단위 테스트 20개 |
| DB 적용 | ⬜ **미적용** (로컬·EC2 모두) |
| `usable_height` | ⚠️ **2.00 m (팀 합의값, 실측 아님)** |
| 이동 거리 정렬 | 🔴 **동작 안 함 — 항상 `null`** |

🔴 이동 거리 정렬이 죽어 있어 **적재 순서가 `A001`부터 코드순으로 고정된다.** 3장 참고.

---

## 2. 좌표 데이터

24칸(A001–A012, B001–B012). 랙 A는 왼쪽 벽, 랙 B는 가운데.

| 랙 | `destination_x` | `destination_y` (12칸) |
|---|---|---|
| A | 5.0 | 9.3 · 10.5 · 11.8 · 13.2 · 14.5 · 15.8 · 17.2 · 18.5 · 19.7 · 21.2 · 22.5 · 23.8 |
| B | 14.5 | (A와 동일) |

공통: `fork_height` 1.325 · `destination_heading` 180.0° · `status` EMPTY

### 왜 접근점만 넣었나

F팀 문서: *접근점까지만 Nav2로 가고 도킹·적재·후진은 차량이 자체 수행한다.*
따라서 도킹 x(2.60 / 11.95)와 적재점 x(0.95 / 10.20)는 **DB에 넣지 않았다.**
넣어 두면 백엔드가 그리로 보내야 하는 것처럼 읽힌다.

`destination_heading`은 스키마상 degree다. 문서의 yaw 3.1416 rad = 180.0°.

---

## 3. 🔴 적재 순서가 코드순으로 고정된다

`PlacementService`의 정렬은 3단계다.

```
1) heightRemaining  오름차순   ← 남는 높이가 가장 적은 칸(빈틈 최소화)
2) travelDistance   오름차순   ← 가까운 칸
3) slotCode         오름차순   ← 동점 시 결정론 보장
```

**1단계가 무력하다.** 24칸의 `usable_height`가 전부 2.00으로 같으므로
`heightRemaining = 2.00 − (화물높이 + 팔레트)`도 24칸 모두 같다.

**2단계도 무력하다.** 후보를 만드는 곳에서 이동 거리를 `null`로 넣는다.

```java
// TransportTaskMeasurementService.java:113
return new PlacementCandidate(
        row.getSlotCode(), ..., row.getDestinationHeading(),
        null, row.getStatus());   // ← travelDistance
```

Nav2 경로 길이를 조회하는 경로가 아직 없다. `nullsLast`라 전부 동점 처리된다.

**결과 — 3단계만 남아 `slotCode` 순으로 결정된다.**

```
A001 → A002 → A003 → ... → A012 → B001 → B002 → ...
```

### 슬롯 코드를 세 자리로 맞춘 이유

`slot_code`가 `VARCHAR`라 정렬이 **문자열 기준**이다. 구 코드 `A1`/`A2`/`A10`은 이렇게 섰다.

```
A1 → A10 → A11 → A12 → A2 → A3 → ... → A9 → B1 → B10 → ...
```

자리수를 채운 `A001` 형식은 문자열 정렬이 곧 번호순이라 이 문제가 사라진다.

### 남은 한계

이제 **한쪽 랙을 앞에서부터 차례로 채운다.** 예측 가능하고 시연에서 이상해 보이지 않는다.
다만 여전히 **거리 기준이 아니다** — 지게차가 어디 있든 항상 `A001`부터 간다.
"최적 적재"가 되려면 9장 2번이 필요하다.

---

## 4. 단위 — 전부 m 로 확정 (2026-08-07 팀 결정)

시뮬 좌표(20 × 30 격자)를 **그대로 미터로 간주**한다. 격자 1칸 = 1 m이고, 이 테이블의 모든
길이 컬럼은 같은 단위를 쓴다. 백엔드는 어떤 변환도 하지 않는다.

| 컬럼 | 단위 |
|---|---|
| `destination_x` / `destination_y` | m (MQTT 계약값 그대로) |
| `fork_height` | m (`task.dropoff`로 그대로 나감) |
| `usable_height` / `usable_width` | m (카메라가 잰 화물 치수와 직접 비교) |

이전 판에서는 "시뮬 단위와 실물 m이 섞여 있다"고 경고했으나, 팀 합의로 m로 확정했다.
변환 코드는 넣지 않는다.

> 🔴 **2026-08-09 정정.** 이 절의 "시뮬 좌표 = m" 전제는 틀렸다. F팀 개정 문서(`backend-mqtt-guide`
> §2)와 `rack_slots.csv`가 **시뮬 = 실물 × 10**임을 명시한다(`approach_x 5.0` ↔ `real_approach_x 0.5`).
> 백엔드가 변환하지 않는 것은 그대로지만, 값을 실물로 읽으면 안 된다.
>
> 같은 정정으로 `fork_height`가 **13.25 → 1.325**로 바뀌었다. 8/5 판 인용값이 잘못이었고,
> 개정 문서·CSV·`shelfHeight` 예시가 모두 1.325다. 자세한 내용은
> `docs/backend-api/traffic-control-migration-report.md` 1·2장.

---

## 5. ⚠️ `usable_height` 2.00은 합의값이지 측정값이 아니다

F팀 문서가 준 것은 좌표와 포크 목표 높이뿐이고, **"한 칸에 얼마나 높은 화물이 들어가는가"는
없다.** 그런데 컬럼이 `NOT NULL` + `CHECK(usable_height > 0)`이라 비워 둘 수 없다.

2026-08-07 팀 결정으로 **2.00 m**를 넣었다(직전 1.00 m는 너무 낮아 대부분의 화물이 거부됐다).

```
requiredHeight = 화물높이 + 팔레트 0.12 + 여유 0.25
2.00 ≥ requiredHeight  →  화물높이 ≤ 1.63 m 까지 적재 가능
```

실측이 나오면 갱신할 것:

```sql
UPDATE storage_slot SET usable_height = <실측 m> WHERE slot_code = 'A001';
```

`usable_width`는 `NULL`로 두었다. NULL은 "폭 제약을 모른다"는 뜻이고 폭 검사를 건너뛴다.
**0으로 채우면 모든 화물이 탈락하므로 절대 쓰지 말 것.**

## 6. 선정 로직

### 통과 조건

```
status == EMPTY
usable_height ≥ 화물높이 + 팔레트(0.12) + 여유(0.25)
usable_width  ≥ 화물폭 + 폭여유(0.10)      ← 둘 중 하나라도 NULL 이면 통과
```

팔레트 높이는 **정확히 한 번만** 더한다. `station_measurement.cargo_height`에는
팔레트가 포함돼 있지 않다(화물만의 높이). DB 값에 미리 더해 저장하면 이중 가산이 된다.

폭 여유(0.10)를 높이 여유(0.25)보다 작게 잡은 이유: 세로는 포크를 올리다 부딪히면 화물이
떨어지지만, 가로는 지게차가 정면으로 밀어 넣는 방향이라 접촉 위험이 낮다.

> ⚠️ `width-clearance`는 `application.yml`에 없어 코드 기본값 0.10이 쓰인다.
> 조정하려면 `storage.placement.width-clearance` 항목을 추가해야 한다.

### 깊이(depth)는 재지 않기로 했다

빠뜨린 것이 아니라 **측정할 수 없어서 재지 않기로 팀이 정했다.** 스테이션 카메라가 정면
하나뿐이라 `ai/src/station/pipeline.py`가 `depth_cm`을 항상 `null`로 낸다(규격 v1.0에 명시).
측정할 수 없는 값을 슬롯 쪽에만 넣어 두면 "검사하는 것처럼" 보이므로 `storage_slot`에도
깊이 컬럼을 두지 않는다.

같은 이유로 **화물 회전(가로↔세로 교환) 판정도 하지 않는다.** 회전 검사는 두 축을 모두
알아야 성립한다. 깊이가 필요하면 측면/상단 카메라가 먼저 있어야 한다.

### 맞는 칸이 없을 때

`NO_AVAILABLE_STORAGE_SLOT` → 운반 작업이 `PLACEMENT_SLOT_UNAVAILABLE`로 실패 처리되고
WebSocket으로 `TRANSPORT_TASK_FAILED`가 나간다. 조용히 넘어가지 않는다.

---

## 7. 동시성

두 작업이 같은 칸을 잡는 것은 **DB 조건부 UPDATE**로 막는다.

```sql
UPDATE storage_slot SET status='RESERVED', reserved_task_id=?
WHERE slot_code=? AND status='EMPTY'    -- 진 쪽은 0행 → 실패 처리
```

추천과 예약 사이에 다른 작업이 채갔다면 `reserveIfEmpty`가 0을 돌려주고 그 작업은
실패한다. 스키마의 `chk_storage_slot_state`가 상태-필드 조합도 강제한다
(RESERVED면 `reserved_task_id` 필수, OCCUPIED면 `stored_cargo_id` 필수 등).

---

## 8. 적용 절차

```bash
mysql -h localhost -u fastbackend -p fast_backend < src/main/resources/db/seed-rack-slots.sql
```

`-p` 뒤에 비밀번호를 붙이지 않는다. 붙이면 셸 기록과 프로세스 목록에 남는다.

- `INSERT IGNORE`라 **여러 번 돌려도 안전하다.** 이미 있는 슬롯의 예약·적재 상태를 덮어쓰지 않는다.
- 기존 테스트 슬롯 `SLOT-01` / `SLOT-02`는 **지우지 않는다.** 지우려면 파일 60–63행 주석을 풀 것
  (예약/적재 중이면 FK 때문에 실패하므로 상태부터 확인).
- 끝에 확인용 `SELECT` 두 개가 붙어 있다. `total_slots`, `rack_slots`, `empty_slots`로 검증.

> 🔴 **구 코드(`A1`~`B12`)로 이미 넣었다면 먼저 지울 것.** `slot_code`가 PK라 `INSERT IGNORE`는
> `A1`과 `A001`을 다른 칸으로 보고 **둘 다 남긴다**(48칸이 된다). 파일의 6번 `DELETE` 주석을
> 풀어 실행하면 된다. `rack_slots`가 24가 아니라 48이면 이 경우다.

---

## 9. 미결 항목

| # | 내용 | 담당 | 영향 |
|---|---|---|---|
| 1 | Nav2 경로 길이 조회 연동 | 백엔드 | 🔴 거리 정렬 무력 → 항상 `A001`부터 |
| 2 | 시드 SQL을 DB에 적용 | 백엔드 | 랙 24칸이 아직 없음 |
| 3 | ~~`fork_height` 13.25 확인~~ | — | ✅ **해결** — 1.325로 정정 (2026-08-09) |
| 4 | `usable_height` 실측 24칸 | 측정 필요 | ⚠️ 지금은 합의값 2.00 — 1.63 m 초과 화물 거부 |
| 5 | `usable_width` 실측(선택) | 측정 필요 | 폭 검사가 항상 통과 중 |

`usable_height`를 2.00으로 올려 **시연을 막던 높이 제한은 풀렸고**, 슬롯 코드를 `A001`
형식으로 바꿔 **적재 순서도 번호순으로 정돈됐다.** 남은 것 중 시연에 보이는 것은 1번
(항상 `A001`부터 가는 것)이고, 정확성 측면에서는 3번이 가장 위험하다.

---

## 10. 관련 파일

| 파일 | 역할 |
|---|---|
| `src/main/resources/db/seed-rack-slots.sql` | 24칸 좌표 시드 |
| `src/main/resources/db/schema.sql` | `storage_slot` 정의 |
| `src/main/java/.../storage/placement/PlacementService.java` | 선정 로직 |
| `src/main/java/.../storage/placement/PlacementProperties.java` | 여유값 설정 |
| `src/main/java/.../transport/service/TransportTaskMeasurementService.java` | 호출·예약 |
| `src/main/resources/mapper/StorageSlotMapper.xml` | 조회·예약 SQL |
| `src/test/java/.../PlacementServiceTest.java` | 단위 테스트 20개 |
| `docs/backend-api/optimal-placement.md` | 기존 설계 문서 |
