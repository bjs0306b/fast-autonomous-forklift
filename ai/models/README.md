# 모델 파일 인벤토리

> 이 폴더의 **모델 파일은 커밋되지 않는다**(`.gitignore`가 `*`, 파일당 100~330MB).
> 그래서 "여기에 무엇이 있어야 하는가"를 이 문서로 남긴다. 새 PC에서 스테이션을
> 돌리려면 아래 표대로 채워야 한다.

## 스테이션이 실제로 쓰는 파일

`ai/src/station/config.py`의 `model_path`가 가리키는 것은 **`end2end.onnx` 하나뿐**이다.
나머지는 백업·롤백용이며, 모델을 바꾸려면 파일명을 바꿔 끼운다.

| 파일 | 정체 | rig 파렛트 검출 | LOCO mAP_50 |
|---|---|---|---|
| **`end2end.onnx`** | **실험8 — 가림 도메인 (현역, 2026-07-29 승격)** | 리그 평가셋 **mAP@0.5 0.9898** (박스 0.9795 / 파렛트 1.0000) | 0.767 |
| `end2end_0763_exp7_aug.onnx` | 실험7 (증강) — 종전 현역 | 12/12, 평균 0.892 | 0.763 (box 0.835) |
| `end2end_0765_exp6_rig.onnx` | 실험6 | 11/12, 평균 0.848 | 0.765 (box 0.832) |
| `end2end_0753_m800_scd.onnx` | 실험5 (공개셋만) | **0/12** | 0.753 |
| `end2end_0710_m640.onnx` | 실험2 (@640) | — | 0.710 |
| `end2end_m800_pretopk3000.onnx` | export 변형 시험본 — **쓰는 곳 없음** | — | — |

실험5가 리그에서 0/12였던 것이 검은 플라스틱 파렛트 도메인 갭이고, 그걸 메운 것이
실험6·7이다. 실험8은 **화물이 파렛트를 덮으면 score 0.101**로 떨어지던 가림 문제를
가림 촬영·재학습으로 메운 것이다(0.101 → 0.920). 경위는
`docs/ai/rtmdet-training-report.md` 참고.

> ⚠️ 이 표가 한동안 **`end2end.onnx`를 "실험7 (현역)"이라고 적고 있었다**(exp8 승격
> 후에도 갱신 안 됨, 2026-08-03 발견). 모델 대장이 틀리면 롤백할 때 엉뚱한 파일을
> 되돌리게 된다.

## 온보드(지게차) 모델 — `onboard_s_2class/`

스테이션과 **클래스가 다르다**: `pallet`·`hole` 2클래스(스테이션은 `box`·`pallet`).

| 파일 | 내용 |
|---|---|
| `epoch_116.pth` | **승격 체크포인트** — G1 hole재현율 99.5% / G2 pallet 100% / G3 오탐 0% |
| `end2end_s640_ep116.onnx` | 위에서 export (INT32 labels 수정본). 젯슨 TRT 재빌드 소스 |
| `best_coco_bbox_mAP_50_epoch_22.pth` | ⚠️ **best 가 아니다** — config `save_best`가 mAP@0.5를 봤는데 그게 ep22에서 1.0 포화해 덜 학습된 걸 골랐다. eval 재판정으로 ep116이 실제 최고 |

## 재학습·재export용 체크포인트

ONNX만으로는 파인튜닝이나 재export를 못 한다. `.pth`가 필요하다.

| 파일 | 내용 |
|---|---|
| **`rtmdet_exp8_occ_20260729.tar.gz`** | **실험8(현역) — ep48.pth + config + 학습곡선 + README** |
| `rtmdet_exp7_aug_20260726.tar.gz` | 실험7 — ep49.pth + onnx + config + README |
| `rtmdet_exp6_rig_20260725.tar.gz` | 실험6 — ep49.pth + onnx + config + README |
| `rtmdet_checkpoints_20260723.tar.gz` | 실험5까지 — -m@800/-m@640/-s 체크포인트 + config |
| `best_coco_bbox_mAP_epoch_99.pth` | 실험5 best (위 tar에도 들어있음) |
| `onboard_s_2class/epoch_116.pth` | 온보드 승격 체크포인트 (tar 없이 파일로 보관) |

> 🔴 **실험8 tar 는 2026-08-03 에 뒤늦게 만들었다.** exp5·6·7 은 학습 직후 만들어
> 뒀는데 **정작 현역인 exp8 만 빠져 있었다** — ONNX 는 로컬에 있어 추론은 안전했지만
> `.pth` 가 GPU 서버 `work_dirs/exp8_occ/` 에만 있었다. 서버는 **프로젝트 종료 후
> 7일 내 삭제**되므로, 그대로 뒀으면 **현역 모델을 재학습·재export 할 수단이
> 사라질 뻔했다.** md5 대조로 반출 확인함.

**실험5 best는 특히 중요하다** — 실험6·7 config의 `load_from`이 이걸 가리킨다.
즉 리그 파인튜닝을 다시 하려면 이 체크포인트에서 warm-start 해야 재현된다.

## 출처

GPU 서버(`~/S15P11A304/ai/work_dirs/`)에서 반출했다. **서버는 프로젝트 종료 후 7일 내
삭제**되므로 위 tar들이 사실상 유일본이다. 서버에도 같은 tar가 홈에 있다
(`~/rtmdet_exp{6,7}_*.tar.gz`, `~/rtmdet_checkpoints_20260723.tar.gz`).

관련 문서: `docs/ai/rtmdet-training-report.md`(실험 이력·판정 근거),
`docs/ai/rig-finetune-runbook.md`(재현 절차), `ai/configs/rtmdet_m_800_*_forklift.py`(학습 config),
`ai/scripts/infer_rig_compare.py`(rig 판정 스크립트).
