# AI / 비전 (EPIC-1)

지게차 화물 인식 파트. 박스 **단일 클래스** 기준으로 RTMDet을 학습해
Jetson Orin Nano에 배포하는 것이 최종 목표다 (FR-101).

| 목표 지표 | 값 |
|---|---|
| 박스 인식 정확도 | mAP@0.5 ≥ 92% |
| 추론 지연 | ≤ 100ms (≥ 10 FPS) |

## 디렉터리

```
ai/
├─ configs/datasets.yaml   데이터셋 소스·카테고리 매핑 정의
├─ src/dataset/
│  ├─ coco.py              COCO 빌더, 중복 판별(image_identity)
│  ├─ sources.py           소스별 변환기 (COCO / SKU-110K CSV)
│  ├─ convert.py           변환 CLI
│  ├─ review.py            라벨 검수 CLI
│  ├─ split.py             train/val 분할 CLI (원본 단위)
│  └─ extract_roboflow.py  Roboflow zip 추출 (Windows 경로 길이 대응)
├─ tests/                  변환 로직 테스트
└─ data/                   원본·변환 결과 (git 제외)
   ├─ raw/                 내려받은 원본
   └─ processed/           변환된 COCO json
```

## 환경 준비

```bash
cd ai
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -e ".[dev]"
```

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

| 클래스 | 수 | 채택 |
|---|---:|---|
| pallet | 120,445 | O — 포크를 꽂는 대상이라 인식 필요 (FR-303 연계) |
| small_load_carrier | 22,151 | O — 박스형 화물 |
| stillage | 5,407 | O — 박스형 화물 |
| pallet_truck | 2,827 | X — 장비 |
| forklift | 598 | X — 장비 |

채택 기준에 따른 수율:

| 기준 | 학습 가능 이미지 | 박스 수 |
|---|---:|---:|
| pallet 제외 | 2,009장 | 27,558 |
| **pallet 포함 (현재)** | **4,885장** | **148,003** |

채택 기준을 바꾸려면 `configs/datasets.yaml`의 `keep_categories`를 수정한다.

### LOCO 경로 처리

LOCO의 `file_name`은 `1613832,4601.jpg` 같은 타임스탬프 basename이라 subset을
합치면 서로 다른 이미지가 충돌한다. 디렉터리가 담긴 `path` 필드를 쓰도록
`path_key: path`, `strip_path_prefix: /dataset/`를 지정해 두었다.

### 다른 소스의 클래스 채택 기준

| 소스 | 채택 | 제외 |
|---|---|---|
| Logistics (21개 클래스) | `cardboard box`, `wood pallet` | 장비·사람·차량·안전장구·화재 등 |
| Carboard Box (4개 클래스) | 전부 (`keep_categories: []`) | 없음 |

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
대부분 LOCO에서 온 파렛트 밀집 장면이라, 파렛트 편중이 68.2% → 56.2%로 개선됐다.

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
- 결과: `data/processed/box_coco.json` (박스 단일 클래스, `category_id=1`)
- 특정 소스만: `--only loco`
- 어노테이션 없는 이미지 유지: `--keep-empty`
- `--verify-images`: 변환 결과의 모든 이미지가 실제로 존재하는지 확인한다.
  경로 규칙이 어긋나면 학습 단계까지 가서야 터지므로, 새 소스를 추가할 때는
  반드시 붙여서 돌린다.

변환기가 하는 일:

1. 소스별로 겹치는 image/annotation id를 전부 새로 발급
2. 카테고리를 박스 하나(`id=1`)로 재매핑
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

파렛트류 124,598개(56.2%) / 박스류 97,179개(43.8%).

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

## train/val 분할 (FR-101-2)

```bash
cd ai
python -m dataset.split --input data/processed/box_coco_clean.json --val-ratio 0.2
```

> ⚠️ **반드시 원본 단위로 묶어 나눠야 한다.**
>
> Roboflow는 원본 1장을 최대 113장까지 증강해 내보낸다. 이미지 단위로 무작위
> 분할하면 같은 사진의 뒤집기·회전본이 train과 val 양쪽에 들어간다.
>
> **실측: 순진한 이미지 단위 분할 시 val 4,321장 중 2,370장(54.8%)이 학습 때 본
> 사진의 변형본이었다.** 모델이 val에서 사실상 외운 것을 맞히게 되어 mAP가 크게
> 부풀려진다. 목표가 mAP 92%인 만큼 이 착시는 치명적이다.

`image_identity()`로 같은 원본을 묶고(21,607장 → 11,208그룹) 그룹 단위로 나눈다.
소스별로 따로 배분해 val이 특정 데이터셋에 쏠리지 않게 한다. 시드 고정(기본 42).

현재 결과:

| | 그룹 | 이미지 | 박스 |
|---|---:|---:|---:|
| train | 8,966 | 17,365 | 177,587 |
| val | 2,242 | 4,242 | 44,030 |

소스별 val 비율 19.1~23.3%로 고르게 배분됐고, **train/val에 걸친 원본은 0개**다.
출력은 `box_coco_train.json` / `box_coco_val.json`.

## 남은 판단 사항

- **Logistics 라벨 품질**: 일부가 Autodistill DETIC 자동 라벨링이고 베이스 모델
  mAP가 76% 수준이다. 표본으로 확인한 종이박스 라벨은 양호했고 LOCO 재수록분은
  박스 수가 정확히 일치해 원본 라벨을 그대로 가져온 것으로 보이나, **전수 검증은
  하지 않았다.** 학습 결과가 기대 이하면 여기를 먼저 의심한다.
- **해상도 불일치**: Logistics·Carboard Box는 640x640 stretch 리사이즈라 종횡비가
  왜곡돼 있다. LOCO만 원본 해상도(1920x1080 등)다.
- **파렛트 비중 56.2%**: 종이박스 기준 mAP가 목표에 못 미치면 pallet 샘플링을
  검토한다 (`configs/datasets.yaml`의 `keep_categories`).

## 테스트

```bash
cd ai
python -m pytest
```
