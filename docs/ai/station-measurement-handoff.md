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
>
> ```
> POST /api/stations/measurements   Content-Type: application/json
> { "measurementId": "...", "status": "ok", "cargoHeight": 0.723,
>   "tippingLevel": "safe", "overhangRatio": 0.057, "measuredAt": "2026-07-31T09:37:48+09:00" }
> ```
>
> **`sessionId`/`stationId`는 보내지 않는다** — 백엔드가 활성 세션을 조회해 붙인다.
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

<<<<<<< HEAD
## 전달 방식 — MQTT 발행 (2026-07-30 구현)

측정 JSON을 백엔드 브로커로 발행한다. **백엔드는 받는 쪽이 이미 완성**돼 있다 —
`fast/station/+/measurement` 구독 → `StationMeasurementService` → DB(`station_measurement`)
→ WebSocket(`/topic/stations/measurements`). 비어 있던 **발행 측**을 `station/publisher.py`로
채웠다.

```bash
# 브로커는 환경변수로만 (실제 IP·인증정보 하드코딩 금지)
MQTT_HOST=<브로커> MQTT_PORT=1883 [MQTT_USERNAME=… MQTT_PASSWORD=…] \
    python src/station/serve.py --once --publish
```

- 토픽 `fast/station/{station_id}/measurement`, **QoS 1 · retained false**
  (`communication-protocol.md` §2 인바운드 규약, `dummy-real-f01-location-publisher.py`와 동일).
- `--publish` 없이 실행하면 종전대로 stdout/파일까지만.

### ⚠️ 남은 확인 (팀)

1. **브로커 접속값**: 백엔드가 GPU서버로 이전(2026-07-28)됐으므로 그 배포처의
   `MQTT_HOST`·포트·인증(평문/TLS)을 스테이션 PC 환경에 주입해야 한다. 젯슨 온보드
   브리지가 `~/mqtt-certs/ca.crt`를 쓰므로 TLS일 가능성 — 백엔드 팀 확인 필요.
2. **DTO 규격 정합**: 현재 백엔드 `StationMeasurementMessage`에는 `tipping`·
   `dimensions.total_height_cm`·`box_measurements`가 **없다**(구버전). 발행 측은 규격(위
   v1.0+)대로 전량 쏘고, **미지원 필드는 백엔드가 무시**하므로 발행은 깨지지 않는다.
   전복·총높이를 DB/화면에 쓰려면 백엔드 DTO·스키마 확장이 필요(백엔드 담당).
3. 실제 브로커 왕복 테스트는 접속값 확보 후. 지금은 토픽·페이로드 순수 로직만 단위
   테스트(`tests/test_publisher.py`).
=======
## 전달 방식 — 확정(REST)

**확정됐다.** 측정 데스크탑이 `POST /api/stations/measurements`로 직접 호출한다.
MQTT(`fast/station/+/measurement`)는 **폐기**됐고 백엔드에서 구독·라우팅·DTO가 모두 제거됐다.
측정 데스크탑은 **DB에 직접 접속하지 않는다** — 이 REST API가 유일한 저장 경로다.

데스크탑 쪽에 남은 작업:

1. 위 표대로 원본 JSON → REST 요청 6필드로 축약
2. **`height_cm` ÷ 100** 으로 cm → m 변환(백엔드는 단위를 추측해 변환하지 않는다)
3. 측정 전에 `POST /api/stations/sessions?cargoId=...`로 세션이 열려 있어야 함(없으면 409)
4. 409 `STATION_MEASUREMENT_ID_DUPLICATED` 는 이미 저장된 결과라는 뜻 — 재전송 불필요
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
