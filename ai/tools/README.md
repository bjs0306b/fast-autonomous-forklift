# tools/

파인튜닝 파이프라인 스테이징(S15P11A304). 실물 리그·미니어처 온보드 도메인 데이터가
도착했을 때 원커맨드로 평가·파인튜닝을 돌리기 위한 하네스. 자세한 절차는
`../PIPELINE_STAGING.md` 참고.

## eval_domain.py — measure-first 평가

새 도메인 데이터가 오면 파인튜닝 전에 먼저 현재 체크포인트를 그 위에서 채점한다.
mmdet의 `mim test` 파이프라인을 그대로 감싸므로 학습 로그의 수치와 100% 같은 방식으로 평가된다.

```bash
python tools/eval_domain.py \
  --config configs/rtmdet_m_800_scd_forklift.py \
  --checkpoint work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth \
  --ann /path/to/domain_val.json \
  --img-root /path/to/domain_images \
  --out work_dirs/eval_domain_result.json
```

- 평가셋 COCO category_id는 프로젝트 스키마(box=1, pallet=2)를 따라야 한다.
- `--kpi`(기본 0.92)로 mAP@0.5 임계값을 조정할 수 있다. 미달이면 `configs/finetune/*.py`로 넘어간다.
- 재현 검증: 기존 `box_coco_val.json` + `rtmdet_m_800_scd` 체크포인트로 돌리면 REPORT.md에
  기록된 mAP@0.5 0.753이 그대로 나온다(2026-07-23 확인됨).

## prelabel.py — 프리라벨(반자동 라벨링)

라벨 없는 원본 이미지 폴더에 현재 모델로 감지를 돌려 CVAT에 바로 임포트 가능한 COCO json을
만든다. 사람은 이걸 열어 수정만 하면 되므로 라벨링 시간을 줄인다.

```bash
python tools/prelabel.py \
  --img-dir /path/to/raw_images \
  --config configs/rtmdet_m_800_scd_forklift.py \
  --checkpoint work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth \
  --threshold 0.3 \
  --out data/processed/prelabel_rig.json
```

- threshold는 재현율 우선(오탐은 사람이 지우기 쉽지만, 미탐은 사람이 새로 그려야 해서 더 비싸다)
  기준으로 기본 0.3으로 낮게 잡았다. 도메인·모델에 따라 조정.
- 출력은 표준 COCO 1.0(`info`/`licenses`/`categories`/`images`/`annotations`)이라 CVAT의
  COCO 임포터가 그대로 읽는다.

## 공통 환경

- `CUDA_VISIBLE_DEVICES=1`만 사용(다른 GPU·팀원 프로세스 절대 건드리지 않음).
- conda env `rtmdet`. numpy는 1.x 고정 — 새 패키지 설치 시 numpy 2로 끌어올리지 않도록 주의.
- 전처리(정규화 mean/std/BGR, keep_ratio 리사이즈+114 패딩)는 config에 이미 박혀 있으므로
  이 도구들을 거치면 별도로 신경 쓸 필요 없다. 직접 onnxruntime 등으로 추론할 때만
  REPORT.md의 "ONNX 저성능 진단" 절 레시피를 그대로 따를 것.
