# 리그 파렛트 도메인 파인튜닝 런북 (2026-07-24)

**왜**: 스테이션 -m(실험5, mAP_50 0.753)이 우리 리그의 **검은 플라스틱 파렛트를 전혀
못 잡는다**. 2026-07-24 실측에서 점수 임계를 0.02까지 내려도 파렛트 영역에 후보가 0건이었고,
같은 프레임의 박스는 0.91~0.92로 잡혔다. 파렛트가 없으면 **편하중(FR-104) 판정이
불가능**하므로(파이프라인이 파렛트를 기준점으로 요구) 32·69 MR의 관문이 여기 걸려 있다.

**무엇**: 자체 촬영 301장(검은 파렛트 도메인)을 기존 학습셋에 **섞어서 단일 run**으로
파인튜닝한다. 2단 순차 파인튜닝은 실험4에서 pallet catastrophic forgetting으로 폐기했고,
실험5의 단일 combined run이 pallet 0.623→0.679로 오히려 올린 전례를 따른다.

---

## 0. 선행 — 라벨 마무리 (로컬)

`ai/data/processed/rig_review.md`의 ②27장 작도 + ③7장 수정 + ①12장 폐기.
CVAT에 `rig_labeled.json`(COCO)과 이미지를 올려 수정 후 COCO로 다시 내보낸다.

```bash
cd ai
conda run -n ai_env python -m dataset.convert --config configs/datasets.yaml --only rig_20260724 --verify-images
```

`rig_20260724` 소스가 datasets.yaml에 추가돼 있다. `--verify-images`로 경로가 맞는지
먼저 확인한다(경로 규칙이 어긋나면 학습 단계까지 가서 터진다).

## 1. 패키징 (로컬)

```bash
cd ai
tar -czf rig_20260724.tar.gz data/raw/rig/20260724 data/processed/rig_labeled.json
```

301장 + 라벨 ≈ 205MB. 이미지가 커서 리포에 커밋하지 않는다(`ai/data/`는 git 제외).

## 2. 서버 반입

GPU 서버(JupyterHub `70.12.130.106`, 계정 `i15a304`)에 올려 `~/S15P11A304/ai/data/raw/rig/`
아래에 푼다. tmux 세션 `cc`에서 작업한다.

```bash
tmux attach -t cc
cd ~/S15P11A304/ai
tar -xzf rig_20260724.tar.gz
ls data/raw/rig/20260724 | wc -l     # 301 (폐기분 제외 시 289)
```

## 3. 학습셋 병합

서버의 combined(LOCO + Cardboard + Logistics + **LSCD/SCD**)에 rig를 **한 학습셋으로**
합친다. SCD는 서버에만 있으므로(`ai/data/raw/coco_style_oneclass.zip`) 병합은 서버에서 한다.

- 네거티브 16장(0121~0136, 파렛트 없음)은 `convert`가 어노테이션 없는 이미지로 버린다.
  `--keep-empty`는 전 소스에 걸려 공개 데이터셋의 부분 라벨까지 살리므로 쓰지 않는다.
  대신 **병합 후 json에 네거티브 이미지 항목만 수동으로 추가**한다(어노테이션 0개).
  오탐 억제에 쓰는 샘플이라 빼면 손해다.
- 분할은 **원본 단위**로. 리그 301장은 한 세션·한 배치라 여기서 val을 떼면 누수가 된다
  → **전량 train**. 평가는 별도 촬영한 실물 리그 평가셋(S15P11A304-148)으로 한다.

## 4. 학습

```bash
cd ~/S15P11A304/ai
CUDA_VISIBLE_DEVICES=1 nohup python tools/train.py \
    configs/rtmdet_m_800_rig.py \
    --work-dir work_dirs/exp6_rig \
    > work_dirs/exp6_rig/train.log 2>&1 &
```

| 항목 | 값 | 근거 |
|---|---|---|
| 베이스 | `rtmdet_m_800_scd_best_ep99.pth` (실험5, 0.753) | 도메인 갭만 메우는 것이므로 처음부터 학습하지 않는다 |
| lr | **1e-4** | 5e-4는 val 붕괴(실험 교훈). 파인튜닝은 1e-4 고정 |
| input_size | 800 | 스테이션 ONNX와 동일해야 한다 |
| epoch | 30~50 | 데이터 추가량이 작아 길게 돌릴 이유가 없다. val 곡선 보고 중단 |
| GPU | **Device 1만** | 팀 공용, 다른 GPU·프로세스 건드리지 않는다 |

⚠️ `CUDA_VISIBLE_DEVICES=1`을 빠뜨리면 다른 팀원 작업과 충돌한다.

## 5. 판정

- **파렛트 검출이 목적**이다. 전체 mAP보다 **pallet AP**와 리그 프레임에서의 실검출을 본다.
- 회귀 감시: box AP가 실험5(0.827) 대비 떨어지면 섞는 비율을 조정한다.
- 실패 판정 기준: 리그 표본에서 파렛트가 score 0.5 이상으로 안 잡히면 실패 → 데이터 보강
  (astraws 플라스틱 파렛트 CC BY 4.0 367장 혼합) 또는 촬영 추가.

## 6. 반출·반영

```bash
# 서버에서 ONNX export (mmdeploy, @800)
python tools/deploy.py ... --work-dir work_dirs/exp6_rig/onnx
```

`end2end.onnx`를 로컬 `ai/models/`로 반출해 교체한다. **기존 0.753 onnx는 백업**
(`end2end_0753_m800_scd.onnx`) — 실험5 때 옛 0.710을 백업해 둔 것과 같은 방식이다.
교체 후 `station/serve.py --once`로 리그 실측을 다시 돌려 `status: ok`(파렛트 검출 +
load_balance 산출)가 나오는지 확인한다. 이게 나오면 FR-104 실검증으로 넘어간다.

## 폴백

파인튜닝이 늦어지거나 실패하면 **파렛트 ROI 고정**(스테이션은 고정 리그이므로 파렛트
자리를 config 상수로) 으로 편하중 검증을 먼저 끝낼 수 있다. 산업용 디멘저너도 파렛트
위치가 고정된 계량대를 쓰므로 설계상 정당하다. 다만 "파렛트를 인식한다"는 아니므로
발표 서술에 주의한다.
