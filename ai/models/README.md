# 모델 파일 인벤토리

> 이 폴더의 **모델 파일은 커밋되지 않는다**(`.gitignore`가 `*`, 파일당 100~330MB).
> 그래서 "여기에 무엇이 있어야 하는가"를 이 문서로 남긴다. 새 PC에서 스테이션을
> 돌리려면 아래 표대로 채워야 한다.

## 스테이션이 실제로 쓰는 파일

`ai/src/station/config.py`의 `model_path`가 가리키는 것은 **`end2end.onnx` 하나뿐**이다.
나머지는 백업·롤백용이며, 모델을 바꾸려면 파일명을 바꿔 끼운다.

| 파일 | 정체 | rig 파렛트 검출 | LOCO mAP_50 |
|---|---|---|---|
| **`end2end.onnx`** | **실험7 (현역)** | **12/12**, 평균 0.892 | 0.763 (box 0.835) |
| `end2end_0765_exp6_rig.onnx` | 실험6 | 11/12, 평균 0.848 | 0.765 (box 0.832) |
| `end2end_0753_m800_scd.onnx` | 실험5 (공개셋만) | **0/12** | 0.753 |
| `end2end_0710_m640.onnx` | 실험2 (@640) | — | 0.710 |

실험5가 리그에서 0/12였던 것이 검은 플라스틱 파렛트 도메인 갭이고, 그걸 메운 것이
실험6·7이다. 경위는 `docs/ai/rtmdet-training-report.md` 참고.

## 재학습·재export용 체크포인트

ONNX만으로는 파인튜닝이나 재export를 못 한다. `.pth`가 필요하다.

| 파일 | 내용 |
|---|---|
| `rtmdet_exp7_aug_20260726.tar.gz` | 실험7 — ep49.pth + onnx + config + README |
| `rtmdet_exp6_rig_20260725.tar.gz` | 실험6 — ep49.pth + onnx + config + README |
| `rtmdet_checkpoints_20260723.tar.gz` | 실험5까지 — -m@800/-m@640/-s 체크포인트 + config |
| `best_coco_bbox_mAP_epoch_99.pth` | 실험5 best (위 tar에도 들어있음) |

**실험5 best는 특히 중요하다** — 실험6·7 config의 `load_from`이 이걸 가리킨다.
즉 리그 파인튜닝을 다시 하려면 이 체크포인트에서 warm-start 해야 재현된다.

## 출처

GPU 서버(`~/S15P11A304/ai/work_dirs/`)에서 반출했다. **서버는 프로젝트 종료 후 7일 내
삭제**되므로 위 tar들이 사실상 유일본이다. 서버에도 같은 tar가 홈에 있다
(`~/rtmdet_exp{6,7}_*.tar.gz`, `~/rtmdet_checkpoints_20260723.tar.gz`).

관련 문서: `docs/ai/rtmdet-training-report.md`(실험 이력·판정 근거),
`docs/ai/rig-finetune-runbook.md`(재현 절차), `ai/configs/rtmdet_m_800_*_forklift.py`(학습 config),
`ai/scripts/infer_rig_compare.py`(rig 판정 스크립트).
