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
| [LOCO](https://github.com/tum-fml/loco) | `data/raw/loco/` | CC BY 4.0 | 물류 창고 특화, COCO 포맷 그대로 |
| [Roboflow Universe — cardboard box](https://universe.roboflow.com/) | `data/raw/roboflow_cardboard/` | 프로젝트별 상이 (대개 CC BY 4.0) | **COCO 포맷으로 내보내기** |
| [SKU-110K](https://github.com/eg4000/SKU110K_CVPR19) | `data/raw/SKU110K/` | 학술·비영리 | 밀집 적재 장면. 기본 비활성 — 사용 전 팀 합의 필요 |

LOCO는 클래스가 5개(`pallet`, `stillage`, `small_load_carrier`, `forklift`,
`pallet_truck`)라서 박스형인 `small_load_carrier`·`stillage`만 채택한다.
지게차·핸드파렛트는 화물이 아니므로 제외한다. 채택 기준을 바꾸려면
`configs/datasets.yaml`의 `keep_categories`를 수정한다.

## 변환 실행

```bash
cd ai
python -m dataset.convert --config configs/datasets.yaml
```

- 소스별 이미지·박스 수와 제외 사유가 출력된다.
- 결과: `data/processed/box_coco.json` (박스 단일 클래스, `category_id=1`)
- 특정 소스만: `--only loco`
- 어노테이션 없는 이미지 유지: `--keep-empty`

변환기가 하는 일:

1. 소스별로 겹치는 image/annotation id를 전부 새로 발급
2. 카테고리를 박스 하나(`id=1`)로 재매핑
3. 이미지 경계를 넘는 bbox는 잘라내고, 완전히 벗어났거나 면적 0이면 제외
4. `file_name`에 소스 이름을 접두사로 붙여 파일명 충돌 방지
5. 어노테이션이 없는 이미지 제거 (부분 라벨링으로 인한 오탐 학습 방지)

> train/val 분할과 라벨 검수는 이 단계가 아니라 **FR-101-2 (S15P11A304-66)**에서 한다.

## 테스트

```bash
cd ai
python -m pytest
```
