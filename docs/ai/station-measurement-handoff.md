# 스테이션 측정 결과 핸드오프 (FR-101/103/104 → FR-201/202)

스테이션이 실물 화물을 재서 내는 **측정 결과 JSON** 규격이다. 적재 위치 산출
(FR-202, S15P11A304-34)이 이 값을 입력으로 쓴다.

- 생산자: `ai/src/station/serve.py` (모델·거리계 배선) → `station/pipeline.build_payload`
- 소비자: 적재 공간 인식·최적 위치 산출 (전지웅) / 백엔드 저장·관제 (김재원)
- 규격 버전: `schema_version: "1.1"`

## 변경 이력

| 버전 | 날짜 | 변경 |
|---|---|---|
| 1.0 | 2026-07-24 | 최초 규격 (dimensions · box_measurements · load_balance) |
| **1.1** | **2026-07-29** | **`tipping` 블록 추가** (전복 위험, FR-103) |

> ⚠️ **필드를 추가하면 버전을 반드시 올린다.**
> `tipping`은 실제로는 2026-07-28(MR !75)에 payload에 들어갔는데 `schema_version`을 1.0에
> 둔 채였다. 호환을 깨는 변경이 아니라 추가라서 넘어갔지만, **소비자에게는 규격이 바뀐 것을
> 알 수단이 없었다.** 그 결과 백엔드는 `tipping`을 모른 채로 두었고, Spring Boot가 모르는
> JSON 필드를 기본으로 무시하는 탓에(`FAIL_ON_UNKNOWN_PROPERTIES=false`) **에러도 로그도 없이
> 조용히 버려졌다.** 발견까지 하루가 걸렸다.

> ## ⚠️ 백엔드 전달은 REST다 — 이 JSON을 그대로 보내지 않는다
>
> 아래 JSON은 **비전 파이프라인의 출력 원본**이다. 백엔드 저장은 MQTT가 아니라 REST이며
> (`fast/station/+/measurement` 토픽은 **제거됐다**), 계약이 원본보다 훨씬 좁다.
>
> | 백엔드 REST 필드 | 이 문서의 원본 필드 | 변환 |
> |---|---|---|
> | `measurementId` | `measurement_id` | 없음 |
> | `status` | `status` | 없음(`dimensions_only` 포함 4값 지원) |
> | **`cargoHeight`** | **`dimensions.height_cm`** | **cm ÷ 100 = m** — 데스크탑이 변환 |
> | `tippingLevel` | `tipping.level` | 소문자 그대로 보내면 백엔드가 대문자로 저장 |
> | `overhangRatio` | `tipping.overhang` | 없음 |
> | `measuredAt` | `measured_at` | 없음(단, **백엔드에 저장 컬럼이 없어 버려진다**) |
> | **`sessionId`** | (측정 시점의 세션) | **필수.** 세션 생성 응답의 `sessionId` 를 그대로 |
>
> ```
> POST /api/stations/measurements   Content-Type: application/json
> { "sessionId": "<세션 생성 응답의 sessionId>",
>   "measurementId": "...", "status": "ok", "cargoHeight": 0.723,
>   "tippingLevel": "safe", "overhangRatio": 0.057, "measuredAt": "2026-07-31T09:37:48+09:00" }
> ```
>
> **`sessionId` 는 필수다(2026-07-31 계약 변경).** 예전에는 보내지 않고 백엔드가 활성 세션을 찾아
> 붙였는데, 그러면 세션 A 가 TTL·강제 해제로 풀리고 세션 B 가 열린 뒤 도착한 A 의 늦은 측정이
> **B 에 잘못 귀속**된다. 이제 요청이 자기 출처 세션을 밝히고 백엔드가 대조한다.
>
> - `POST /api/stations/sessions?cargoId=...` 응답의 `sessionId` 를 그대로 싣는다
> - **재전송할 때도 원래 값을 유지한다** — 새 세션 값으로 바꾸면 막으려던 오귀속이 발생한다
> - 활성 세션과 다르면 409 `STATION_SESSION_MISMATCH`, 활성 세션이 없으면 409 `STATION_SESSION_NOT_ACTIVE`
> - 두 오류는 **재시도 대상이 아니다**(stale). 네트워크·5xx 재시도는 하되 `sessionId` 는 바꾸지 않는다
> - 누락·공백이면 400 — 백엔드가 값을 추측하거나 활성 세션으로 대체하지 않는다
>
> `stationId` 는 여전히 보내지 않는다(FR-202 에서 컬럼이 사라졌다).
> 나머지 필드(detection/distance/load_balance/box_measurements/miniature/total_height)는
> **백엔드에 저장되지 않는다.** 전체 규격은 `docs/backend-message/communication-protocol.md`
> §Measurement Station v2.0 (REST) 참고.

## 한눈에

```jsonc
{
  "schema_version": "1.1",
  "measurement_id": "station-1-20260729-093509-0001",
  "station_id": "station-1",
  "measured_at": "2026-07-29T09:35:09+09:00",
  "status": "ok",

  "dimensions": {
    "height_cm": 72.3,                  // 화물만 (파렛트 제외)
    "total_height_cm": 84.3,            // ★ 화물 + 파렛트 — 적재 판단은 이 값
    "pallet_height_cm": 12.0,
    "width_cm": 102.2,
    "depth_cm": null,                   // 정면 카메라라 측정 불가 — 항상 null
    "miniature_scale": 10,
    "miniature_height_mm": 72.3,
    "miniature_total_height_mm": 84.3,  // ★ 미니어처 환산 총높이
    "miniature_width_mm": 102.2,
    "tilt_deg": null                    // 적용된 롤 보정각. null이면 보정 안 함
  },

  "tipping": {                          // 전복 위험 (FR-103)
    "assessable": true,
    "level": "safe",                    // safe | warning | danger
    "static_stable": true,
    "support_offset": 0.027,            // 1.0 = 무게중심이 파렛트 끝(전복 한계)
    "margin": 0.973,
    "direction": null,                  // "left" | "right" | null
    "aspect_ratio": 0.71,
    "overhang": 0.0,
    "message": "안정 (무게중심 이탈 3%, 한계까지 97% 여유)"
  },

  "load_balance": {                     // 편하중 (FR-104)
    "eccentric": false,
    "direction": [],                    // ["left"] | ["right"] | []
    "ratio_x": 0.03,
    "ratio_y": null,                    // 정면 뷰라 앞뒤는 판정 안 함 — 항상 null
    "magnitude": 0.03,
    "threshold": 0.3,
    "message": "정상 (치우침 0.03 ≤ 0.3)"
  },

  "detection": { "box_count": 5, "boxes": [...], "pallet": {...} },
  "distance":  { "front_cm": 236.0, "std_cm": 0.0, "frames_used": 100 },
  "box_measurements": [ /* 박스별 치수 — 아래 참고 */ ]
}
```

## 적재 로직이 실제로 쓸 값

| 목적 | 필드 | 단위 |
|---|---|---|
| **적재 높이(백엔드 REST)** | `dimensions.height_cm` ÷ 100 → `cargoHeight` | **m (화물만)** |
| 미니어처 환산 | `dimensions.miniature_total_height_mm` | mm |
| **적재 폭** | `dimensions.width_cm` | cm |
| 안전 판정 | `tipping.level`, `load_balance.eccentric` | — |

> ⚠️ **백엔드 REST로는 `height_cm`(화물만)을 m로 바꿔 보낼 것.** 팔레트 높이를 미리 더하지 않는다.
>
> 랙 간섭 판단에 총높이가 필요한 것은 맞지만, **팔레트 높이를 더하는 주체가 백엔드로 정해졌다** —
> 백엔드가 설정값 `storage.placement.pallet-height-m`(0.12)를 `PlacementService`에서 **정확히 한 번**
> 더한다(`requiredHeight = cargoHeight + 0.12 + clearance`). `total_height_cm`을 `cargoHeight`로 보내면
> **팔레트 높이가 두 번 더해진다.**
>
> `total_height_cm` / `miniature_total_height_mm` 자체는 이 문서의 원본 출력으로 남지만,
> **백엔드 FR-202 계산 입력으로는 쓰지 않는다.**

> ⚠️ **깊이(`depth_cm`)는 항상 null.** 정면 단일 카메라라 앞뒤를 못 잰다(명세 §2.2).
> 적재 단위가 파렛트(T-11 고정 규격 1100×1100mm)이므로 깊이는 규격값을 쓰면 된다.

### 단위 규약
- `*_cm` = **실물** 치수, `miniature_*_mm` = **미니어처**(실물 ÷ 10) 치수
- 미니어처 데모에서 실제로 옮기는 물체는 1/10 축소본이므로, 트윈·배치 계산은
  `miniature_*` 쪽을 쓰는 편이 헷갈리지 않는다. 둘 다 같은 측정에서 나온 값이다.

## status — 값이 언제 채워지나

**키는 status와 무관하게 항상 존재한다.** 값이 없으면 `null`이거나
`{"assessable": false, "reason": ...}`이므로, 키 유무를 분기할 필요가 없다.

| status | 뜻 | dimensions | tipping | load_balance |
|---|---|---|---|---|
| `ok` | 정상 | ✅ | ✅ | ✅ |
| `dimensions_only` | **파렛트 미검출** — 치수만 | ✅ | `assessable:false` | `null` |
| `no_detection` | 박스 없음 | `null` | `assessable:false` | `null` |
| `unreliable` | 거리 측정 실패 | `null` | `assessable:false` | `null` |

→ **`status == "ok"`가 아니면 적재 판단에 쓰지 말 것.** 재측정을 요청하는 게 맞다.

## tipping — 전복 위험 (FR-103)

무게중심이 **지지면(파렛트)** 을 벗어나는지로 판정한다. 편하중과 다른 질문이다.

- `support_offset` = |화물 무게중심 − 파렛트 중심| ÷ (파렛트 반폭).
  **1.0이면 무게중심이 파렛트 끝** = 물리적 전복 한계.
- `level`: `safe`(<0.60) / `warning`(0.60~0.85) / `danger`(≥0.85).
  종횡비 ≥1.5이거나 화물이 파렛트 밖으로 크게 나가면 한 단계 격상.
- `static_stable`: **정지 상태에서 넘어지는가**. `level`은 **운반 기준**이라 다를 수 있다 —
  세워두면 안 넘어져도(`static_stable: true`) 지게차로 옮기기엔 부적합할 수 있다
  (실측 사례: 여유 20%에 하단 박스 42%가 파렛트 밖).

⚠️ 임계 0.60/0.85는 물리 한계 전에 안전 여유를 둔 **공학적 판단**이며, 실측 표본은
5건뿐이다. 지게차 실주행(Sprint 3) 검증 후 조정될 수 있다.

## load_balance — 편하중 (FR-104)

- `ratio_x` = (화물 무게중심 − 파렛트 중심) ÷ 화물 반폭. **부호 있음**(+오른쪽).
- `|ratio_x| > threshold(0.3)`이면 `eccentric: true`.
- `ratio_y`는 **항상 null** — 정면 뷰에서 위아래는 편하중 축이 아니다.

## box_measurements — 박스별 치수

`dimensions`는 적재물 **전체 외곽(hull)** 하나지만, 박스를 개별로 배치한다면 이 배열을 쓴다.

```jsonc
{ "bbox_px": [1078,433,422,259], "score": 0.97,
  "height_cm": 30.0, "width_cm": 49.1,
  "miniature_height_mm": 30.0, "miniature_width_mm": 49.1 }
```

⚠️ 거리계가 값을 하나만 주므로 **모든 박스가 카메라에서 비슷한 거리(같은 앞면)** 여야
정확하다. 앞뒤로 벌어지면 뒤쪽 박스의 오차가 커진다.

## 정확도 (실측 근거)

| 항목 | 결과 | 조건 |
|---|---|---|
| 치수 오차 | **평균 0.66mm / 최대 2.10mm** (미니어처) | 단일 박스 54/54 통과, 거리 129~294cm |
| 박스별 치수 | 98.1% 통과 | 다중 박스 |
| 박스·파렛트 검출 | mAP@0.5 **0.9898** | 리그 평가셋 30장 |

KPI는 미니어처 ≤4mm. 상세는 `docs/ai/station-dim-eval-plan.md`, `docs/ai/rig-eval-map.md`.

**측정 조건**: 화물을 **프레임 중앙**에 둘 것(가장자리는 렌즈 왜곡으로 오차↑),
사람은 프레임 밖, 거리계 빔이 화물 앞면을 정조준.

## 샘플 얻기

```
python src/station/serve.py --once --out sample.json          # 라이브
python src/station/serve.py --once --image <사진> --distance 200   # 사진으로
```

## 전달 방식 — 확정(REST)

**확정됐다.** 측정 데스크탑이 `POST /api/stations/measurements`로 직접 호출한다.
MQTT(`fast/station/+/measurement`)는 **폐기**됐고 백엔드에서 구독·라우팅·DTO가 모두 제거됐다.
측정 데스크탑은 **DB에 직접 접속하지 않는다** — 이 REST API가 유일한 저장 경로다.

데스크탑 쪽 구현 완료 (2026-07-31, `station/rest_client.py`):

```bash
STATION_API_BASE=http://<백엔드> python src/station/serve.py --once --publish
```

1. ✅ 원본 JSON → REST 요청 6필드로 축약 (`to_request`)
2. ✅ **`height_cm` ÷ 100** 으로 cm → m 변환
3. ✅ **세션 개설·종료 자동화**(2026-07-31) — `--cargo-id`를 주면 데스크탑이 직접 연다. 아래 참조
4. ✅ 409 `STATION_MEASUREMENT_ID_DUPLICATED`는 **성공으로 취급**(이미 저장됨, 재전송 불필요)

> ⚠️ **`cargoHeight`에 `total_height_cm`을 넣지 말 것.** 백엔드가 적재 판단에서 파렛트
> 0.12m를 따로 더하므로(`requiredHeight = cargoHeight + 0.12 + clearance`) 총높이를 보내면
> 파렛트를 두 번 더한다. 단위 변환과 이 구분은 `tests/test_rest_client.py`가 회귀 검증한다.

**서버 확인 완료 (2026-07-31)**: 백엔드가 `http://70.12.246.250:8080`에 떠 있다. 실제
페이로드를 보내 **6필드가 전부 검증을 통과**했다(400이 아니라 409 `SESSION_NOT_ACTIVE` —
세션만 없었고 규격은 맞았다). 200까지의 왕복은 세션을 열어야 하므로 미확인.

## 세션 — 단일 설비 뮤텍스

백엔드는 `station_state`의 `active_session_id` **한 행**으로 설비 점유를 지킨다. 열려
있는 동안 다른 화물은 세션을 못 연다(409 `STATION_ALREADY_OCCUPIED`).

```bash
STATION_API_BASE=http://70.12.246.250:8080 \
  python src/station/serve.py --once --publish --cargo-id cargo-1
```

### ⚠️ `--cargo-id` 없이 보내면 남의 화물에 붙는다

측정 요청에는 **cargoId가 없다.** 백엔드는 `findActiveSession()`으로 **현재 활성 세션**을
찾아 붙일 뿐 화물을 대조하지 않는다. 누가 다른 화물로 세션을 열어둔 상태에서 보내면
**그 화물에 조용히 기록되고, 사후에 알아낼 방법이 없다.**

→ **측정 데스크탑이 자기 세션을 열고 자기가 닫는다.** ("누가 여는지"의 답이다.)

### 세션은 측정 **앞에** 연다

백엔드 문서상 정상 흐름("세션 시작 → 측정 → 결과 등록")을 그대로 따른다. 세션이
**"지금 이 화물을 측정 중"** 이라는 뜻을 갖고, 둘을 얻는다:

- 관제가 `GET /api/stations/sessions/active`로 **설비 점유를 실시간 표시**할 수 있다
- 설비가 이미 점유 중이면 **측정을 시작하기 전에** 409로 튕겨 헛수고를 막는다

전송 직전에 여는 배치도 검토했다(잠김 구간이 요청 한 번으로 줄어든다). 하지만 그러면
세션이 몇 밀리초만 존재해 위 둘을 다 잃는다. 실패 시 `unreliable` 측정을 남겨 자동
해제하는 절충안도 있었으나, **측정 실패 행을 DB에 쌓지 않기로** 했다(2026-07-31 결정).

### ⚠️ 세션은 스스로 안 풀린다

백엔드에 **TTL·자동 만료가 없다.** 게다가 종료는 **측정이 저장된 뒤에만** 된다
(409 `STATION_MEASUREMENT_NOT_COMPLETED`). 전송이 실패한 채 끝나면 설비가 잠긴 채 남고
**아무도 새 측정을 시작할 수 없다.**

`measurement_session` 컨텍스트 매니저가 보장하는 범위는 이만큼이다:

| 종료 경로 | 닫히나 |
|---|---|
| 정상 종료 · 예외 · Ctrl-C | ✅ |
| `SIGTERM`(kill, 서비스 정지) | ✅ 핸들러로 예외 전환 |
| `SIGKILL`(kill -9) · 전원 차단 | ❌ 불가능 |
| **측정 도중 예외**(카메라 미개방·해상도 불일치·모델 없음) | ❌ 백엔드가 종료를 거부 |
| 측정 전송 실패 후 종료 | ❌ 위와 같음 |

세션을 측정 앞에 열기로 한 대가가 가운데 줄이다 — 그 구간이 수 초로 늘어난다.
`serve.py`는 이때 종료 코드 **2**로 끝나(성공 0·측정실패 1과 구분) 잠김을 알린다.

마지막 셋은 사람이 푼다:

```bash
python src/station/serve.py --release-session            # 조회 후 종료 시도
python src/station/serve.py --release-session --abandon  # 측정 없는 세션 강제 해제
```

`--abandon`은 `unreliable` 측정을 하나 남겨 잠금을 푼다. **없는 측정을 지어내는 것이
아니라 "이 세션은 측정에 실패했다"를 기록하는 것**이다.

> **백엔드에 요청할 것**: 세션 TTL(예: 10분 무활동 시 자동 해제) 또는 강제 해제
> 엔드포인트. 지금은 클라이언트가 아무리 조심해도 `kill -9` 한 번이면 설비가 잠긴다.
