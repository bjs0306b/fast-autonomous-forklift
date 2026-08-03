# 온보드 -s 파인튜닝 런북 (S15P11A304-145)

> 대상: 지게차 온보드 카메라의 포크 정렬 비전 — **pallet·hole 2클래스** (box는 프리라벨 과검출로 폐기, §1)
> 관련: 144(촬영·라벨링) · 68(TensorRT 엔진) · 152~154(정렬 제어)
> 데이터 기록: `docs/ai/onboard-dataset-batches.md` · 라벨 규약: `docs/ai/onboard-hole-label-guide.md`

## 0. 승격 게이트 — 착수 전에 정한다

> "좋아졌나"를 사후에 묻지 않는다. exp8에서 통했던 방식 그대로, **통과 조건을 먼저 적고** 셋 다 만족할 때만 온보드 모델을 교체한다.

| # | 조건 | 판정 기준 |
|---|---|---|
| **G1** | **hole을 찾는다** | 다른 날 촬영한 eval셋에서 **hole 재현율 ≥ 95%** (score 0.4 기준). 포크 정렬의 실패 모드는 "구멍을 못 찾는 것"이므로 정밀도보다 재현율을 본다 |
| **G2** | **pallet을 놓치지 않는다** | eval셋 pallet 재현율 ≥ 98%. 파렛트를 놓치면 접근 자체가 시작되지 않는다 |
| **G3** | **헛것을 보지 않는다** | **네거티브 프레임(파렛트 없음)에서 hole 오탐 ≤ 2%**. 카펫·의자 다리의 어두운 틈을 구멍으로 잡으면 포크가 허공을 찌른다 |
| **G4** | **실시간을 유지한다** | Orin Nano TensorRT FP16에서 **≤ 100ms(≥10FPS)**. 현재 -s @640이 9.28ms라 여유는 크지만, 클래스가 늘어난 뒤 재측정한다 |

G3의 임계가 낮은 이유는 실측 근거가 있다 — 현역 exp8로 네거티브 42장에 프리라벨을 돌렸을 때 **box를 35개 잡았다.** 어두운 틈은 실제로 오탐을 부른다.

⚠️ **스테이션 -m(exp8)은 건드리지 않는다.** 온보드 -s는 **`pallet`·`hole`**, 스테이션 -m은 **`box`·`pallet`** — 둘 다 2클래스지만 구성이 다르다(§1 참조). exp8 계보를 손대면 이미 통과한 치수 KPI(평균 0.66mm)와 편하중 판정을 다시 검증해야 한다.

## 1. 라벨링 — 사람이 pallet·hole만 그린다 (2클래스)

**최종 클래스는 `pallet`·`hole` 2개다.** `box`는 넣으려다 뺐다 — 근거는 아래 "box를 뺀 이유".

사람이 그리는 이유는 **승격 게이트**다. G1(hole 재현율)·G2(pallet 재현율)·G3(hole 오탐)이 pallet·hole에 걸려 있고, 현역 exp8로 이 358장에 돌려보면 pallet 검출률 **14%**(정면 0%), hole은 **모르는 클래스라 0개**다. 프리라벨이 무의미하니 빈 화면부터 사람이 그린다(규약은 `onboard-hole-label-guide.md` §3).

```bash
# ① 사람이 pallet·hole을 그린다 — 로컬 라벨러. 중단해도 같은 명령으로 이어진다
python -m dataset.label_onboard \
    --images data/processed/cvat_onboard_pass1 \
    --out data/labels/onboard_cvat_pallet_hole.json

# ② 버스트 전체로 펼친다 (208 → 358장)
python -m dataset.onboard_bursts expand \
    --plan data/labels/onboard_burst_plan.json \
    --coco data/labels/onboard_cvat_pallet_hole.json \
    --out data/labels/onboard_cvat.json

# ③ 구간 단위 train/val 분할 + 이미지 스테이징
python -m dataset.split_onboard \
    --coco data/labels/onboard_cvat.json \
    --images data/raw/onboard/train_20260729/b01_upright \
    --out-dir data/processed        # ← 명시할 것. 없으면 실행 위치(src)에 샌다
```

결과(2026-07-30): **train 291장**(pallet 261·hole 570) / **val 67장**(pallet 67·hole 134) / 네거티브 30장. 이미지 358장이 `data/processed/staged_images_onboard/`.

### box를 뺀 이유 (2026-07-30)

원래 box를 **amodal pallet의 가림물 학습 보조**로 넣으려 했다 — 사람은 게이트가 걸린 pallet·hole만 그리고, 게이트 없는 box는 exp8 프리라벨로 채우는 분업이었다. 실제로 돌려보니 프리라벨이 못 쓸 수준이었다:

| 항목 | 실측 |
|---|---|
| box 장당 | **2.4개** (기대 1개) — 구간 1·5에서 기대의 2배 초과 |
| 그중 파렛트 오인 | **22%** — 흰 미니어처 파렛트를 통째로 box로 잡음(score 0.87까지) |
| 나머지 오탐 | 배경(의자·신발) + 같은 박스 중복 검출 |

파렛트를 box로 가르치면 **pallet 검출(G2)을 오히려 해친다.** 오탐이 파렛트·배경·중복으로 섞여 단일 기하 필터로 못 거르고, box는 **런타임 미사용 보조 클래스**라 얻는 것보다 위험이 컸다. `audit`이 이 판정을 냈다(구간 1·5 "과검출 의심").

> 이 판정은 착수 전에 정한 기준이 발동한 것이다 — "기대의 2배 초과 또는 검수 감당 안 되면 box를 버리고 2클래스로 간다"(§1 아래 box 판정 기준). `prelabel`·`merge_coco`·`audit`·`cvat_onboard_labels.json`은 **그 시도의 산물로 남겨둔다** — box를 되살릴 근거가 생기면 다시 쓴다.

⚠️ **2클래스 warm-start 함정** — config `rtmdet_s_640_onboard_forklift.py` 결정 2 참고. exp7 -s가 (box, pallet) 2클래스라 새 (pallet, hole)와 채널이 같아, 체크포인트를 그대로 로드하면 box 가중치가 pallet 자리에 조용히 들어간다. **rtm_cls 키를 제거한 사본**을 load_from으로 줘야 한다.

### 라벨러 조작

| 키 | 동작 |
|---|---|
| 드래그 | 현재 클래스로 박스 추가 — **마우스 떼면 바로 확정**(208장 × 최대 5개라 확인키를 두면 손이 두 배) |
| `1` / `2` | 클래스 전환 — 1=pallet, 2=hole |
| `U` / `C` | 마지막 박스 취소 / 이 프레임 전부 지우기 |
| `SPACE`·`ENTER` / `B` | 저장하고 다음 / 이전 |
| `G` / `S` / `Q` | 프레임 번호로 이동 / 지금 저장 / 저장하고 종료 |

구멍 경계가 안 보이면 **`--view-width`를 올린다**(예: 1920). 마우스를 따라다니는 돋보기 창을 먼저 넣어봤는데 시야를 가려서 오히려 방해가 됐고, 화면 전체를 키우는 쪽이 같은 효과를 낸다.

**프레임을 넘길 때마다 저장한다.** 208장은 몇 시간짜리 작업이라 중간에 날리면 안 된다. 진행 상황은 `<out>.progress.json`에 따로 남긴다 — "아직 안 본 프레임"과 "보고 나서 라벨 없다고 판정한 프레임"은 다르고, 네거티브 7장이 후자다. `Q`로 나가면 **현재 프레임은 완료로 표시하지 않으므로** 다음에 거기서 이어진다.

그리는 중에 가이드 §4 검수 규칙을 화면 아래에 띄운다 — pallet 없이 hole만, pallet 밖 hole, 종횡비 1.5:1 미만, 세로 16px 미만, hole 5개 이상. **막지는 않는다**(규약을 어긴 프레임이 정말 예외인지는 사람이 봐야 한다).

라벨러는 category를 `box`=1·`pallet`=2·`hole`=3으로 낸다(box는 0개). 학습은 config `classes=('pallet','hole')`로 box를 무시하고 2클래스만 로드한다.

### 358장을 다 그리지는 않는다 — 버스트 61개

`shoot.py --burst 6`으로 찍었으므로 358장은 **61개 버스트**(6장 ×57 · 7장 ×2 · 1장 ×2)다. 버스트 안에서 피사체가 안 움직였다면 6장을 따로 그리는 건 같은 그림을 여섯 번 그리는 것이다.

`dataset.onboard_bursts`가 버스트별 이동량을 재서 그릴 프레임을 고른다.

```bash
python -m dataset.onboard_bursts plan \
    --images data/raw/onboard/train_20260729/b01_upright \
    --out data/labels/onboard_burst_plan.json \
    --keyframe-dir data/processed/cvat_onboard_pass1     # 라벨러가 이 폴더를 읽는다
```

| | 값 |
|---|---|
| 전파 가능 버스트 | **32개** (182장) → 키프레임 32장만 그린다 |
| 개별 라벨 버스트 | 29개 (176장) |
| **그릴 프레임** | **208장 / 358장 (58%)** — 150장 절감 |
| 그중 구간 8 네거티브 | 7장 (라벨 없음 확인만) |

`ai/configs/cvat_onboard_labels.json`은 **CVAT으로 우회할 때만** 쓰는 태스크 스키마다(`pallet`·`hole` 두 개뿐). 로컬 라벨러를 쓰면 필요 없다.

라벨 후 키프레임 라벨을 버스트 전체로 펼치는 것이 §1의 `expand` 단계다.

⚠️ **판정은 밝기가 아니라 이동량으로 한다.** 처음엔 프레임 간 평균절대차를 썼는데 **자동 노출 때문에 양쪽으로 다 틀린다** — 버스트 353~358은 차이 12.3으로 '움직임'이 나왔지만 실제 이동은 2.6px(카펫만 찍힌 네거티브에서 노출이 튄 것)이고, 반대로 버스트 135~140은 차이 7.8로 '정지'인데 실제로는 4.3px 밀려 있었다. 그래서 파렛트가 들어오는 하단-중앙(x320~1088 · y300~640)을 밝기·콘트라스트 정규화한 뒤 `phaseCorrelate`로 **몇 px 밀렸는지**를 잰다.

임계 **4px**의 근거는 `hole`이다. 개구부는 1280×800에서 세로 약 25~60px(가이드 하한 16px)이므로 4px는 그 1/6 이하이고, 사람이 그은 bbox 자체의 흔들림과 같은 수준이다. `pallet`은 훨씬 크므로 더 관대하다.

구간 5(79장)와 구간 8(42장)은 거의 전부 정지라 절감이 여기서 나온다. 반대로 구간 2(회전)는 12개 버스트 중 **11개가 개별 라벨**이다 — 손으로 돌리며 찍었으니 당연하다.

### 고전 CV 자동 라벨도 시도했다 — 폐기

이 장면은 흰 파렛트 / 갈색 박스 / 회색 카펫이 색으로 갈리므로 자동 라벨이 될 법했다. 세 가지를 시도했고 전부 실패했다.

| 시도 | 결과 |
|---|---|
| 어두운 슬롯을 앵커로 구멍 먼저 찾기 | 60장에서 **1개**. 구멍 안쪽이 어둡지 않다 — 흰 PLA라 빛이 들어와 베이지로 보인다 |
| 납작한 흰 덩어리(종횡비 6.9)로 파렛트 먼저 | 60/60 검출되나 **경계가 부정확**. 흰 신발·의자·벽과 붙어 프레임 끝까지 늘어난다 |
| 파렛트 ROI 안에서 Otsu로 구멍 | 구멍이 **파렛트 bbox를 그대로 복사**(ROI에 카펫이 섞여 파렛트-카펫 경계를 가름). 필터를 걸면 이번엔 대부분 놓친다 |

`box`도 **붙어 있는 박스들을 하나로 뭉친다** — 색 연결성분의 구조적 한계라 임계 조정으로 못 고친다.

리그의 `pallet_autolabel`(최고 7/40)과 같은 결론이다. **이 도메인에서 통한 것은 언제나 학습 기반이었고**(`pallet_from_boxes`는 회귀 + 명도 하이브리드였다), 고전 CV 단독은 아니었다. 스크립트는 남기지 않았다.

## 2. 데이터 준비

**val은 구간 단위로 뗀다 — 랜덤 분할 금지.**

버스트 촬영이라 한 배치 안의 프레임은 서로 거의 같다. 랜덤으로 나누면 train과 val에 사실상 동일한 사진이 갈려 들어가 val이 부풀려진다(리그에서 이미 겪은 오염 패턴).

| 용도 | 구간 | 장수 |
|---|---|---|
| train | 1·2·3·5·6·8 | 291 |
| val(모니터링) | **4·7** | 67 |

`onboard_coco_train.json` / `onboard_coco_val.json`으로 내보내고 이미지는 `data/processed/staged_images_onboard/`에 놓는다.

⚠️ 이 val은 **학습 모니터링용**이다. KPI 판정은 **다른 날 촬영할 eval셋 100~150장**으로 한다. 같은 날 같은 배치에서 나눈 것으로 KPI를 주장하면 안 된다.

## 3. 학습

config: `ai/configs/rtmdet_s_640_onboard_forklift.py`

### 선행: warm-start 체크포인트 strip (한 번만)

exp7 -s가 (box,pallet) 2클래스라 새 (pallet,hole)와 rtm_cls 채널이 같아, 그대로 로드하면 box 가중치가 pallet 자리에 들어간다(config 결정 3). 분류 헤드를 떼어낸 사본을 만든다:

```bash
# GPU 서버, ai/ 에서. rtmdet_s_forklift_nocls.pth 생성 (6키 제거, 40.5MB)
python -c "import torch; c=torch.load('work_dirs/rtmdet_s_forklift/best_coco_bbox_mAP_epoch_5.pth', map_location='cpu'); \
    sd=c['state_dict']; [sd.pop(k) for k in list(sd) if 'bbox_head.rtm_cls' in k]; \
    torch.save(c, 'work_dirs/rtmdet_s_forklift/rtmdet_s_forklift_nocls.pth')"
```

로그에 `missing keys ... bbox_head.rtm_cls...`가 뜨면 정상(그 헤드만 랜덤 초기화). `load checkpoint from ...nocls.pth`도 확인한다.

### 실행

⚠️ **`train_rtmdet.sh`는 config를 인자가 아니라 `CONFIG` 환경변수로 받는다.** 첫 인자(`$1`)는 **모드**(`smoke`/`full`)다. config를 인자로 넘기면 **조용히 기본 config(`rtmdet_s_forklift.py`, 옛 box 학습)로 돈다** — 에러 없이. (2026-07-30에 실제로 이 함정에 걸려 5분치 GPU를 날렸다.)

```bash
# GPU 서버, ai/ 에서. conda 활성화 필수 — 없으면 mim을 못 찾는다
source /opt/tljh/user/etc/profile.d/conda.sh && conda activate rtmdet
CONFIG=configs/rtmdet_s_640_onboard_forklift.py \
    WORKDIR=work_dirs/onboard_s_2class GPU=1 \
    bash scripts/train_rtmdet.sh
```

- **`CONFIG=`** — 안 주면 기본값(옛 config). 반드시 명시.
- **`WORKDIR=`** — 안 주면 `work_dirs/rtmdet_s_forklift`에 써서 **warm-start 원본 체크포인트와 섞인다.** 새 디렉토리로 분리. `load_from`은 config대로 원본 위치의 nocls.pth를 읽으므로 충돌 없다.
- **`GPU=1`** — Device1 고정. `smoke`(200장·1ep)는 첫 인자로: `... bash scripts/train_rtmdet.sh smoke`.
- 스크립트가 내부에서 nohup detached로 던지고 PID·로그 경로를 출력한다.

**진행 확인**: `iter/epoch`가 **19**여야 한다(291장 batch16). 329처럼 크면 잘못된 config·batch다. loss_cls는 rtm_cls 랜덤 초기화라 처음 높게(≈1.5) 시작하는 게 정상.

config에 박아둔 결정 (근거는 파일 상단 주석):

1. **공개 데이터를 섞지 않는다** — 공개셋에는 hole 라벨이 없어, 섞으면 "파렛트가 보이는데 구멍은 없다"고 가르치게 된다. 온보드 -s의 도메인은 데모 환경 그 자체라 공개 도메인 성능은 KPI가 아니다
2. **box를 뺀 2클래스**(pallet·hole) — box 프리라벨 과검출로 폐기(§1). `num_classes=2`, `classes=('pallet','hole')`
3. **warm-start는 exp7 -s의 rtm_cls를 떼어낸 사본** — exp7이 (box,pallet) 2클래스라 채널이 같아, 그대로 로드하면 box 가중치가 pallet 자리에 들어간다. strip 명령은 config 결정 2 주석
4. **상하 뒤집기 증강 금지** — 파렛트는 상판이 위, 구멍은 그 아래라는 상하 관계가 hole 판별의 단서다

기타: lr **1e-4** 고정(5e-4는 val 붕괴 전례) · `filter_empty_gt=False`(네거티브 보존) · 오버샘플이 필요하면 **JSON 복제**로(RepeatDataset은 stage2 전환을 깨뜨린다) · `RandomResize` 하한 0.5(hole이 640에서 27px이라 0.1까지 줄이면 뭉개진다).

## 4. 엔진 빌드 → 온보드 반영

블로커는 68에서 모두 해소됐다. 절차는 `docs/ai/onboard-tensorrt-runbook.md` 그대로:

1. GPU 서버에서 `configs/deploy/detection_tensorrt_static_onboard.py`로 재-export
2. ONNX를 젯슨으로 전송 (**서버 아웃바운드 차단 → 노트북 경유**)
3. 젯슨에서 `trtexec --fp16` 빌드 (플러그인 `libmmdeploy_tensorrt_ops.so` 필요, mmdeploy 1.3.1로 버전 일치)
4. **입력 640 고정** — config의 `img_size`를 바꿨다면 엔진도 다시 빌드해야 한다

⚠️ **추론 경로의 180° 회전을 잊지 말 것.** 카메라를 뒤집어 장착했으므로 촬영과 동일한 회전을 추론에도 적용해야 한다. 한쪽만 돌리면 학습·추론 도메인이 정반대가 되고, 증상은 "아무것도 검출 안 됨"이라 원인 찾기가 어렵다.

## 5. 알려진 리스크

- **hole이 작다.** 640 입력에서 세로 약 27px. 카메라를 더 높이 달거나 멀리서 접근하면 라벨 하한(원본 16px)에 걸린다 — 설치 각 θ ≤ 20° 유지가 전제다
- **조명 변형이 학습셋에 없다.** 358장 전부 같은 실내 조명이라 데모 조명이 다르면 성능이 떨어질 수 있다. 5~6배치 보충 권장
- **박스 색 변형이 없다**(전부 갈색). 의도된 결정이며 전제는 **데모에 흰 박스를 내보내지 않는 것**
