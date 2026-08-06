# AI / 비전 (EPIC-1)

지게차 화물 인식 파트. RTMDet 기반이고 **배포처가 둘로 갈린다**(2026-07-22 결정, MR !36).

| | 어디서 | 모델 | 클래스 | 하는 일 |
|---|---|---|---|---|
| 측정 스테이션 | 측정 PC (추론은 젯슨 위임) | RTMDet-**m** @800 | `box` · `pallet` | 화물 치수·전복·편하중 |
| 온보드 | Jetson Orin Nano | RTMDet-**s** @640 TRT FP16 | `pallet` · `hole` | 포크 정렬 |

⚠️ **둘 다 2클래스지만 내용이 다르다.** 온보드 `box` 는 흰 파렛트를 오인해 2026-07-30에
폐기했다. "온보드 3클래스" 라고 적힌 옛 기술이 있으면 그건 틀린 것이다.

| 목표 지표 | 값 | 실측 |
|---|---|---|
| 박스 인식 정확도 | mAP@0.5 ≥ 92% | ✅ 리그 평가셋 **0.9898**(exp8) |
| 추론 지연 | ≤ 100ms (≥ 10 FPS) | ✅ 온보드 TRT **10.18ms**(엔진) · 종단 44.96ms/22.2fps |
| 치수 오차 | ≤ 4mm | ✅ 평균 **0.66mm** / 최대 2.10mm |

## 디렉터리

> ⚠️ **이 README 본문은 데이터셋 구축(FR-101-1·2) 단계의 기록이다.** 그 뒤로 `ai/` 는
> **측정 스테이션**·**온보드 포크 정렬**까지 담게 됐고, 그쪽 문서는 아래 표를 따라간다.

```
ai/
├─ configs/               데이터셋 정의(datasets.yaml) · 학습 config
├─ src/
│  ├─ dataset/            데이터셋 변환·검수·분할·라벨링 (이 README 본문)
│  ├─ perception/         검출기 래퍼(OnnxDetector·TrtDetector) · 포크 정렬 오차 산출
│  ├─ control/            fork_servo — 포크 정렬 상태기계(SEARCH~RETREAT~INSERT)
│  ├─ station/            측정 스테이션 서비스(카메라·TF-Nova·추론·REST 전송)
│  └─ rtmdet_ext/         MMDetection 확장
├─ scripts/               실행 진입점 (아래 표)
├─ models/                ONNX·체크포인트 (대용량은 git 제외)
├─ tests/                 변환 로직 · fork_servo 테스트
└─ data/                  원본·변환 결과 (git 제외)
   ├─ raw/                내려받은 원본
   └─ processed/          변환된 COCO json
```

### 어디를 볼 것인가

| 하려는 일 | 진입점 | 문서 |
|---|---|---|
| 화물 치수·전복·편하중 측정 | `python -m station.serve` | `docs/ai/station-measurement-handoff.md` |
| 포크 정렬 실주행 | `scripts/onboard_fork_align_node.py` | `docs/ai/onboard-fork-align-runbook.md` |
| 젯슨 TRT 엔진 빌드 | `scripts/onboard_trt_bench.py` | `docs/ai/onboard-tensorrt-runbook.md` |
| 온보드 재학습·라벨링 | `src/dataset/label_onboard.py` | `docs/ai/onboard-finetune-runbook.md` · `onboard-hole-label-guide.md` |
| 데이터셋 구축 | `src/dataset/convert.py` | **이 README 본문** |

## 환경 준비

```bash
cd ai
pip install -e ".[dev]"
```

> **conda 환경(`ai_env`)에서는 반드시 `conda run`으로 실행한다.**
>
> ```
> ❌  C:\...\envs\ai_env\python.exe -m dataset.convert ...
> ✅  conda run -n ai_env python -m dataset.convert ...
> ```
>
> `python.exe`를 전체 경로로 직접 호출하면 환경이 활성화되지 않아 `Library\bin`이 DLL
> 검색 경로에 들어가지 않는다. 그러면 MKL을 못 찾아 `numpy`의 `@`(matmul)와 `linalg`에서
> **프로세스가 그대로 죽는다**(`0xc06d007f`). import와 원소별 연산은 멀쩡히 되므로
> numpy 설치가 깨진 것처럼 보이기 쉽다.

`src` 레이아웃이라 **editable 설치가 필요**하다. 설치하지 않으면
`python -m dataset.convert`가 모듈을 찾지 못한다.

## 데이터셋 내려받기 (FR-101-1)

용량이 커서 리포에 커밋하지 않는다. 아래 경로에 직접 배치한다.

| 소스 | 배치 경로 | 라이선스 | 원본 규모 |
|---|---|---|---|
| [LOCO](https://github.com/tum-fml/loco) | `data/raw/loco/` | CC BY 4.0 | 5,097장 (아래 절차) |
| [Roboflow "Logistics" v2](https://universe.roboflow.com/large-benchmark-datasets/logistics-sz9jr) | `data/raw/logistics/` | CC BY 4.0 | 99,238장 |
| [Roboflow "Carboard Box" v4](https://universe.roboflow.com/carboard-box/carboard-box) | `data/raw/roboflow_cardboard/` | CC BY 4.0 | 10,393장 (원본 ~1.1k 증강) |
| [SKU-110K](https://github.com/eg4000/SKU110K_CVPR19) | `data/raw/SKU110K/` | 학술·비영리 | 기본 비활성 — 사용 전 팀 합의 필요 |

Roboflow 데이터셋은 로그인 후 `Download Dataset → COCO → Download zip to computer`로
받는다. **`Show download code`는 API 키가 노출되므로 쓰지 않는다.**

Roboflow zip은 아래 도구로 푼다. 원본 URL을 base64로 인코딩한 파일명이 섞여 있어
Windows 경로 상한(260자)에 걸리는데, 해당 파일만 짧은 이름으로 바꾸고 어노테이션의
`file_name`도 함께 고쳐 준다 (Logistics의 경우 94,729개 중 28개).

```bash
cd ai
python -m dataset.extract_roboflow ~/Downloads/Logistics.v2i.coco.zip data/raw/logistics
python -m dataset.extract_roboflow "~/Downloads/Carboard Box.v4i.coco.zip" data/raw/roboflow_cardboard
```

### LOCO 내려받기

```bash
cd ai/data/raw/loco
# 어노테이션 (103MB)
curl -sL -o loco-all-v1.json \
  https://raw.githubusercontent.com/tum-fml/loco/main/rgb/loco-all-v1.json
# 이미지 (733MB) — 풀면 dataset/subset-1..5/ 구조가 나온다
curl -L -o dataset.zip https://go.mytum.de/239870
unzip dataset.zip
```

어노테이션 5,097장이 모두 `dataset/` 아래 실제 파일과 1:1로 매칭되는 것을 확인했다
(`--verify-images`로 재확인 가능). zip에는 라벨 없는 이미지 4,518장이 더 들어 있는데,
변환 시 자동으로 제외된다.

### LOCO 클래스 채택 기준

LOCO 전체는 이미지 5,097장 / 어노테이션 151,428개이며 클래스 분포는 아래와 같다.

| 원본 클래스 | 수 | 우리 클래스 |
|---|---:|---|
| pallet | 120,445 | **pallet** |
| small_load_carrier | 22,151 | **box** |
| stillage | 5,407 | **box** |
| pallet_truck | 2,827 | 제외 (장비) |
| forklift | 598 | 제외 (장비) |

LOCO 단독 수율: 이미지 4,885장 / 어노테이션 148,003개 (box 27,558 + pallet 120,445).

매핑을 바꾸려면 `configs/datasets.yaml`의 `class_map`을 수정한다.

### LOCO 경로 처리

LOCO의 `file_name`은 `1613832,4601.jpg` 같은 타임스탬프 basename이라 subset을
합치면 서로 다른 이미지가 충돌한다. 디렉터리가 담긴 `path` 필드를 쓰도록
`path_key: path`, `strip_path_prefix: /dataset/`를 지정해 두었다.

### 다른 소스의 클래스 채택 기준

| 소스 | box로 | pallet로 | 제외 |
|---|---|---|---|
| Logistics (21개 클래스) | `cardboard box` | `wood pallet` | 장비·사람·차량·안전장구·화재 등 |
| Carboard Box (4개 클래스) | 전부 (`"*"`) | 없음 | 없음 |

Carboard Box의 카테고리는 이름이 제각각이지만 **실물을 확인한 결과 전부
종이박스**였다: `Cardboard-Box`(더미, 어노테이션 0), `0`(13,977), `box`(4,958),
`snake-in-cardboard-boxes`(71, 박스에 숨는 밈 사진이지만 bbox는 박스에 있음).
하위 데이터셋을 병합하며 이름만 갈라진 것이라 전체를 채택한다.

## 데이터셋 간 중복 (중요)

공개 데이터셋은 서로를 재수록한다. **LOCO 5,097장 중 3,830장(75%)이 Roboflow
Logistics에 그대로 들어 있었다.** 파일명이 각각
`subset-1/1564563638.9526272.jpg` 와 `1564563638-9526272_jpg.rf.<해시>.jpg` 라서
단순 파일명 비교로는 걸러지지 않는다.

`image_identity()`가 디렉터리·증강 접미사를 떼고 구분자를 통일해 같은 원본을
알아본다. 소스 선언 순서가 우선순위이며, **같은 `group`(데이터셋) 안에서는
중복으로 보지 않는다** — 증강본은 원본을 공유하지만 의도된 학습 데이터이기 때문이다.

LOCO를 맨 앞에 두는 이유:

- 수동 라벨 원본이다
- `small_load_carrier` / `stillage` / `pallet` 구분이 살아 있어 나중에 채택 기준을
  바꿀 여지가 남는다 (Logistics는 이를 `wood pallet` 하나로 뭉갰다)

중복 제거는 데이터를 줄이는 게 아니라 **품질을 올린다**. 걸러진 Logistics 이미지가
대부분 LOCO에서 온 파렛트 밀집 장면이라, 파렛트 비중이 68.2% → 56.2%로 내려갔다.

> 2클래스로 전환한 뒤로는 이 비중이 **편중이 아니라 두 클래스의 분포**다. 단일 클래스
> 시절에는 파렛트가 박스에 섞여 있어 편중으로 보였을 뿐이다.

| 단계 | 파렛트 비중 |
|---|---:|
| LOCO 단독 | 81.4% |
| + Carboard Box | 70.9% |
| + Logistics | 68.2% |
| **+ 중복 제거 (현재)** | **56.2%** |

## 변환 실행

```bash
cd ai
python -m dataset.convert --config configs/datasets.yaml --verify-images
```

- 소스별 이미지·박스 수와 제외 사유가 출력된다.
- 결과: `data/processed/box_coco.json` (`box`=1, `pallet`=2)
- 특정 소스만: `--only loco`
- 어노테이션 없는 이미지 유지: `--keep-empty`
- `--verify-images`: 변환 결과의 모든 이미지가 실제로 존재하는지 확인한다.
  경로 규칙이 어긋나면 학습 단계까지 가서야 터지므로, 새 소스를 추가할 때는
  반드시 붙여서 돌린다.

변환기가 하는 일:

1. 소스별로 겹치는 image/annotation id를 전부 새로 발급
2. 소스별 카테고리를 우리 클래스(`box`/`pallet`)로 재매핑
3. 이미지 경계를 넘는 bbox는 잘라내고, 완전히 벗어났거나 면적 0이면 제외
4. `file_name`에 소스 이름을 접두사로 붙여 파일명 충돌 방지
5. **데이터셋 간 같은 원본 사진 제거** (위 "데이터셋 간 중복" 참고)
6. 어노테이션이 없는 이미지 제거 (부분 라벨링으로 인한 오탐 학습 방지)

### 현재 결과 (2026-07-20)

**이미지 21,609장 / 박스 221,777개** — 전부 파일 존재 확인, 데이터셋 간 중복 0건

| 데이터셋 | 이미지 | 박스 | 비중 |
|---|---:|---:|---:|
| LOCO | 4,885 | 148,003 | 66.7% |
| Logistics | 6,331 | 51,894 | 23.4% |
| Carboard Box | 10,393 | 21,880 | 9.9% |

**클래스별**: `pallet` 124,598개(56.2%) / `box` 97,179개(43.8%).

소스별 기여가 뚜렷하게 갈린다 — LOCO가 파렛트를 대부분 대고, Carboard Box는 박스만,
Logistics는 둘 다 조금씩 낸다.

| 소스 | box | pallet |
|---|---:|---:|
| LOCO | 27,558 | 120,445 |
| Carboard Box | 21,880 | — |
| Logistics | 47,741 | 4,153 |

Logistics는 99,238장 중 6,331장(6%)만 남았다. 대상 클래스가 없는 장면(화재·교통
등)이 84,332장, LOCO·Carboard Box와 중복이 4,937장이다.

## 라벨 검수 (FR-101-2)

```bash
cd ai
python -m dataset.review --input data/processed/box_coco.json \
    --output data/processed/box_coco_clean.json
```

품질 지표를 출력하고 **명백히 못 쓸 어노테이션만** 제거한다. 실측 결과 라벨
품질이 전반적으로 양호해서 기본 임계값을 보수적으로 잡았다.

| 기준 | 기본값 | 실측 제거량 |
|---|---|---:|
| IoU 중복 (같은 이미지 내) | 0.9 이상 | 109 |
| 종횡비 | 20:1 초과 | 45 |
| 최소 면적 | 16px 미만 | 6 |
| **합계** | | **160 (0.07%)** |

`--min-area` / `--max-aspect` / `--dup-iou`로 조절한다.

**전체 프레임을 덮는 박스 1,133개는 걸러내지 않는다.** 실물을 확인한 결과
96.5%가 '박스 클로즈업 한 장'이라 정상 라벨이었다. 나머지 40개는 적재 단위
전체를 하나로 본 라벨이다.

참고 분포: 박스 면적 p1=150 / p50=5,727 / p99=345,625px, 이미지당 박스
p50=2 / p90=29 / 최대 345개.

## train/val/test 분할 (FR-101-2)

```bash
cd ai
python -m dataset.split --input data/processed/box_coco_clean.json
```

> ⚠️ **반드시 원본 단위로 묶어 나눠야 한다.**
>
> Roboflow는 원본 1장을 최대 113장까지 증강해 내보낸다. 이미지 단위로 무작위
> 분할하면 같은 사진의 뒤집기·회전본이 여러 세트에 갈라져 들어간다.
>
> **실측: 순진한 이미지 단위 분할 시 val 4,321장 중 2,370장(54.8%)이 학습 때 본
> 사진의 변형본이었다.** 모델이 val에서 사실상 외운 것을 맞히게 되어 mAP가 크게
> 부풀려진다. 목표가 mAP 92%인 만큼 이 착시는 치명적이다.

`image_identity()`로 같은 원본을 묶고(21,607장 → 11,208그룹) 그룹 단위로 나눈다.
소스별로 따로 배분해 특정 데이터셋에 쏠리지 않게 한다. 시드 고정(기본 42).

기본 비율은 train 0.7 / val 0.15 / test 0.15이며 `--val-ratio` / `--test-ratio`로
바꾼다. `--test-ratio 0`이면 test를 만들지 않는다.

| | 그룹 | 이미지 | 박스 |
|---|---:|---:|---:|
| train | 7,846 | 15,170 | 155,022 |
| val | 1,681 | 3,155 | 33,726 |
| test | 1,681 | 3,282 | 32,869 |

소스별 val·test 비율이 14.1~17.3%로 고르게 배분됐고, **세트 간에 걸친 원본은 0개**다.

### val과 test를 나누는 이유

**val**은 학습(FR-101-3)·양자화(FR-101-4)에서 하이퍼파라미터와 모델을 고르며
반복해 들여다보게 된다. 그러면 모델이 아니라 *우리의 선택*이 val에 과적합해
val 점수가 낙관적으로 편향된다.

**test**는 학습·튜닝 내내 열지 않다가 최종 보고(FR-101-6) 직전에 한 번만 연다.
mAP@0.5 ≥ 92% 달성 여부는 이 수치로 판단한다.

> ⚠️ **이 test도 어디까지나 공개 데이터셋 기준이다.** 실제 인식 대상은 미니어처
> 환경의 종이박스라 조명·배경·스케일·카메라(Jetson CSI)가 모두 다르다. 공개
> 데이터 test에서 92%가 나와도 미니어처에서 같은 성능이 나온다는 보장은 없다.
>
> **최종 인수 판단은 우리 환경에서 직접 찍어 라벨링한 평가셋으로 해야 하며,
> 그건 아직 없다.** FR-103-4(미니어처 스케일 매핑·검증)와 맞물리는 사안이라
> 스프린트 2에서 촬영·라벨링 계획이 필요하다.

## TF-Nova 거리계 (FR-103-1)

```bash
cd ai
# 측정 (기본 스케일 보정 자동 적용)
conda run -n ai_env python -m perception.tfnova --port COM3 --seconds 2
# 캘리브레이션 (자로 잰 거리와 비교해 비율 계수 산출)
conda run -n ai_env python -m perception.tfnova --port COM3 --seconds 2 --calibrate 55
```

Benewake TF-Nova 단일 점 거리계. UART 115200 8N1, 9바이트 프레임(헤더 `0x59 0x59`),
실측 약 200Hz. `FrameParser`는 시리얼과 분리돼 있어 하드웨어 없이 테스트된다.

### 신뢰성 처리

- **체크섬 검증** — 실패 시 1바이트만 전진해 재동기화한다. 2바이트를 건너뛰면 잡음과
  겹친 진짜 헤더를 밟고 지나가 정상 프레임까지 잃는다 (테스트가 잡아낸 버그).
- **거리 0은 '대상 없음'** — 매뉴얼 명시. 신뢰도가 100으로 나와도 거리값이 아니므로
  버린다 (실측에서 신뢰도 100 + 거리 0 조합을 확인).
- **중앙값 집계** — 사람이 빔 앞을 지나가는 순간 튐에 평균은 끌려가지만 중앙값은 버틴다.
- 유효 프레임이 부족하면 값을 만들지 않고 `MeasurementUnreliable`로 거부한다.

### 캘리브레이션 (2026-07-23 스테이션 마운트 5점 재캘리)

| 실제(cm) | 55 | 70 | 77 | 120 | 200 |
|---|---|---|---|---|---|
| 원값(cm) | 53 | 67 | 75 | 116 | 194 |

원값 ≈ 0.968×실제(절편≈0) → 거리에 **비례**해 과소하게 읽는다. 깨끗한 벽 200cm에서
원값 194(-6cm)가 비율 오차를 확정(고정 오프셋이면 198이어야). → **비율 계수 1.033
보정**(코드 기본값, `실제 = 원값 × 1.033`). 잔차 전 구간 ±1cm 이내.
**거리계는 치수 KPI 병목이 아니다** — 데모 범위 55~80cm에선 오차 <0.5cm.

> 2026-07-20 탁상 캘리는 상수 -2cm로 보였으나(손 줄자 편향 추정) 정조준·클린월
> 5점이 비율을 확정. 스케일은 마운트에 따라 달라지므로 `--calibrate`로 재확인한다.
> (Nova가 카메라보다 ~1.15cm 앞 → 스케일은 카메라 기준 거리에 맞춰짐.)

### 운용 주의 (실측에서 확인)

- **표적이 빔 띠를 다 덮어야 한다.** 14° 축 기준 빔 폭은 30cm에서 7.4cm, 1.5m에서
  37cm다. 표적이 좁으면 주변 반사가 섞여 **실제보다 짧게** 나온다 (핸드폰 30cm →
  10cm로 읽힌 실사례). 매뉴얼도 "모든 광점이 표적 위에 있을 때" 기준이라 명시한다.
- **장착면에서 띄워서 설치한다.** 센서 높이가 12mm라 바닥에 눕히면 빔이 표면을 스쳐
  가까운 지점을 잡는다.
- 유리·거울 같은 정반사면은 부적합하다 (무광 확산면 기준).
- **빔 띠 방향 미확정**: 판매 페이지는 가로 14°, 매뉴얼 3.4절은 세로 14°로 서로
  다르다. 장착 전에 뷰어로 손을 좌우/상하로 움직여 넓은 축을 확인할 것.

## 남은 판단 사항

- **Logistics 라벨 품질**: 일부가 Autodistill DETIC 자동 라벨링이고 베이스 모델
  mAP가 76% 수준이다. 표본으로 확인한 종이박스 라벨은 양호했고 LOCO 재수록분은
  박스 수가 정확히 일치해 원본 라벨을 그대로 가져온 것으로 보이나, **전수 검증은
  하지 않았다.** 학습 결과가 기대 이하면 여기를 먼저 의심한다.
- **해상도 불일치**: Logistics·Carboard Box는 640x640 stretch 리사이즈라 종횡비가
  왜곡돼 있다. LOCO만 원본 해상도(1920x1080 등)다.
- **클래스 불균형**: `pallet` 56.2% / `box` 43.8%. 2클래스 학습으로는 문제없는 수준이나,
  박스 쪽 mAP가 목표에 못 미치면 `configs/datasets.yaml`의 `class_map`으로 조정한다.
- **실행은 `conda run`으로**: `python.exe`를 전체 경로로 직접 호출하면 환경이 활성화되지
  않아 MKL DLL을 못 찾고 `numpy`의 `@`·`linalg`에서 프로세스가 죽는다.

## 테스트

```bash
cd ai
conda run -n ai_env python -m pytest
```

⚠️ `python.exe` 를 전체 경로로 직접 호출하면 환경이 활성화되지 않아 MKL DLL 을 못 찾고
`numpy` 의 `@`·`linalg` 에서 프로세스가 죽는다. 항상 `conda run` 으로 실행한다.
