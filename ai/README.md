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
├─ src/dataset/            공개 데이터셋 → COCO 변환기
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

| 소스 | 배치 경로 | 라이선스 | 비고 |
|---|---|---|---|
| [LOCO](https://github.com/tum-fml/loco) | `data/raw/loco/` | CC BY 4.0 | 물류 창고 특화, COCO 포맷 그대로 (아래 절차) |
| [Roboflow Universe — cardboard box](https://universe.roboflow.com/) | `data/raw/roboflow_cardboard/` | 프로젝트별 상이 (대개 CC BY 4.0) | **COCO 포맷으로 내보내기** |
| [SKU-110K](https://github.com/eg4000/SKU110K_CVPR19) | `data/raw/SKU110K/` | 학술·비영리 | 밀집 적재 장면. 기본 비활성 — 사용 전 팀 합의 필요 |

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

> **주의 — 클래스 편중**: pallet이 채택분의 81%를 차지한다. 단일 클래스로 합치면
> 모델이 파렛트 위주로 학습될 수 있어, 종이박스 기준 mAP가 목표(92%)에 못 미칠
> 위험이 있다. Roboflow cardboard 비중 조절이나 pallet 샘플링으로 대응한다.
> 판단은 학습(FR-101-3) 결과를 보고 조정한다.

채택 기준을 바꾸려면 `configs/datasets.yaml`의 `keep_categories`를 수정한다.

### LOCO 경로 처리

LOCO의 `file_name`은 `1613832,4601.jpg` 같은 타임스탬프 basename이라 subset을
합치면 서로 다른 이미지가 충돌한다. 디렉터리가 담긴 `path` 필드를 쓰도록
`path_key: path`, `strip_path_prefix: /dataset/`를 지정해 두었다.

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

LOCO 단독 실행 결과 (2026-07-20 기준):

```
loco: 이미지 5,097장 / 박스 148,003개
  제외 — 대상 외 카테고리 3,425
[정리] 어노테이션 없는 이미지 212장 제거
[검증] 이미지 4,885장 모두 존재
완료 → data/processed/box_coco.json
  이미지 4,885장 / 박스 148,003개
```

변환기가 하는 일:

1. 소스별로 겹치는 image/annotation id를 전부 새로 발급
2. 카테고리를 박스 하나(`id=1`)로 재매핑
3. 이미지 경계를 넘는 bbox는 잘라내고, 완전히 벗어났거나 면적 0이면 제외
4. `file_name`에 소스 이름을 접두사로 붙여 파일명 충돌 방지
5. 어노테이션이 없는 이미지 제거 (부분 라벨링으로 인한 오탐 학습 방지)

> train/val 분할과 라벨 검수는 이 단계가 아니라 **FR-101-2 (S15P11A304-66)**에서 한다.

FR-101-2로 넘길 실측 소견:

- bbox 면적 중앙값 2,688px, 이미지당 박스 중앙값 21개 — 밀집 장면이 많다.
- **면적 100px 미만 박스가 816개**(최소 1px). 학습에 해로울 수 있어 검수 때
  하한선을 정해 걸러낼지 판단이 필요하다.

## 테스트

```bash
cd ai
python -m pytest
```
