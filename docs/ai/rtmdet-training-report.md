# RTMDet 박스·파렛트 자동 학습·튜닝 로그

> **이 문서는 GPU 서버(`~/S15P11A304/ai/work_dirs/REPORT.md`)의 보존본이다.** SSAFY GPU 서버는
> 프로젝트 종료 후 7일 내 삭제되므로, 실험 이력·판정 근거를 잃지 않도록 레포로 옮겼다
> (2026-07-27). 본문의 경로(`work_dirs/...`, `data/processed/...`)는 **서버 기준**이다.
>
> 함께 보존된 것: `ai/configs/rtmdet_m_800_rig_forklift.py`(실험6)·
> `ai/configs/rtmdet_m_800_astraws_forklift.py`(실험7)·`ai/scripts/infer_rig_compare.py`(rig 판정).
> 재현 절차는 `docs/ai/rig-finetune-runbook.md` 참고.
>
> **최종 채택 = 실험7**(rig 파렛트 12/12, LOCO mAP_50 0.763). 스테이션 배포본은 로컬
> `ai/models/end2end.onnx`이고, 이전 모델들도 `end2end_0765_exp6_rig.onnx` 등으로 보존돼 있다.

무인 루프 기록. 목표였던 `coco/bbox_mAP_50 ≥ 0.72`는 미달(0.710)이나, **2026-07-22 사용자
판단으로 이 단계(공개 데이터 튜닝 루프)를 마감**했다 — 진단 결과 파렛트 약점이 LOCO 특유의
밀집·원거리 장면에서 기인하며, 데모 시나리오(근접 단일 파렛트)와는 무관하다고 판단했기 때문.
자세한 내용은 하단 "최종 결론" 참고. GPU는 `CUDA_VISIBLE_DEVICES=1` 고정, 팀원 Isaac Sim과 공유.

**2026-07-22 추가 지시 — 실험4 (해상도 800 재개)**: 측정 추론이 Jetson→스테이션 PC로 이관되며
(MR !36) 10FPS 제약이 사라졌고 스테이션 모델은 RTMDet-m@800으로 확정됨에 따라, 조기 중단했던
실험3의 해상도 800 방향을 재개한다. 목적은 mAP 경쟁이 아니라 **"미니어처 파인튜닝의 베이스를
타깃 해상도(800)로 확보"**하는 것 — mAP_50이 0.72를 넘든 말든 무관하며 실험2(@640, 0.710)와
비슷한 수준이면 성공. 완주 후 @640과 비교만 남기고(실험4로 기록), 체크포인트 백업 후 정지·대기.
공개 데이터 튜닝 루프 종료 판단(위) 자체는 변경 없음 — 이번 재개는 별도 목적(해상도 베이스 확보)임.

## 최종 산출물 (2026-07-23 갱신 — 실험5 완주)

**갱신 사유**: 실험4(해상도 800, LOCO+Cardboard)는 epoch26에서 중단되고, SCD를 추가한 실험5
(해상도 800, LOCO+Cardboard+SCD)가 그 목적(스테이션 PC용 800 해상도 모델)을 계승해 완주했다.
따라서 아래 "800 해상도 모델"은 실험4가 아닌 **실험5의 체크포인트**를 가리킨다.

| 용도 | 체크포인트 | config | mAP_50 | box | pallet |
|---|---|---|---|---|---|
| **800 해상도 모델(스테이션 PC 타깃, 실험5)** | `work_dirs/rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth` | `configs/rtmdet_m_800_scd_forklift.py` | **0.753** | 0.827 | 0.679 |
| 640 해상도 모델(실험2) | `work_dirs/rtmdet_m_loco_cardboard/best_coco_bbox_mAP_epoch_100.pth` | `configs/rtmdet_m_forklift.py` | 0.710 | 0.798 | 0.623 |
| **fallback**(Jetson에서 -m이 ~10FPS 미달 시) | `work_dirs/rtmdet_s_loco_cardboard/best_coco_bbox_mAP_epoch_99.pth` | `configs/rtmdet_s_forklift.py` | 0.680 | - | - |
| ONNX(640 모델 기준) | `work_dirs/rtmdet_m_loco_cardboard/onnx_export/end2end.onnx` | mmdeploy `detection_onnxruntime_dynamic` | 성공(onnxruntime 추론 검증 완료) | - | - |
| **ONNX(800 모델, 스테이션 배포용, 2026-07-23 추가)** | `work_dirs/rtmdet_m_800_scd/onnx_export/end2end.onnx` | mmdeploy `detection_onnxruntime_dynamic`(800 입력) | 성공(torch 오라클과 완전 일치 검증) | - | - |

## 최종 결론 (2026-07-22, 사용자 판단)

- 실험2(RTMDet-m, 640해상도, batch16, lr5e-5, LOCO+Cardboard 100ep)를 이 단계 최종 모델로 확정.
  mAP_50 0.710(목표 0.72 대비 -0.01), box 0.798 / pallet 0.623.
- **진단 재해석**: classwise 분석(아래)에서 클래스 혼동은 없고 파렛트의 배경 오탐·미탐·로컬라이제이션이
  모두 약하다는 것을 확인했으나, 사용자 판단으로는 이 약점이 **LOCO 데이터 특유의 밀집·원거리
  파렛트 장면**에서 비롯된 것이며 **실제 데모 시나리오(근접 단일 파렛트)와는 무관**하다고 결론.
  따라서 공개 데이터로 이 gap을 메우려는 추가 튜닝(서브셋 스크리닝 등)은 비용 대비 실익이 낮다고
  판단해 중단.
- **스크리닝 미실행**: 진단 기반 후보 config 3종(mosaic 완화/bbox copy-paste/loss_bbox 가중치, 아래
  참고)은 작성·스모크 테스트까지 진행했으나 실제 스크리닝 학습은 실행하지 않음. 향후 데모 시나리오에
  특화된 파인튜닝이 필요해지면 재사용 가능하도록 레포에 보존.
- **다음 단계**(이 세션 범위 밖): (1) 데모 시나리오(근접 단일 파렛트)를 대표하는 미니 평가셋 구축 후
  그에 맞춘 파인튜닝, (2) TensorRT 변환·Jetson 배포. ONNX export는 이 세션에서 시도해 성공(아래).
  TensorRT 엔진 빌드는 Jetson 대상 하드웨어에서 진행 필요.

**메모리 정책 (2026-07-22 갱신)**: 기존 "학습 프로세스 20GB 이내" 폐기. 새 정책 — GPU1(46GB)에서
Isaac Sim 여유분 10GB를 항상 남기고 학습 프로세스는 최대 ~35GB까지 사용 가능. 판단 기준은
**"학습+Isaac Sim 합산이 40GB를 넘지 않게"**. OOM 시 배치 한 단계 축소 후 재시도, Isaac Sim이 커져
합산이 40GB에 근접하면 내 쪽을 줄인다(팀원 프로세스는 여전히 불가침). 이 정책 덕에 배치를 키워
iteration 수·학습 시간을 줄일 수 있다(예: RTMDet-m batch16→32, lr도 배치 비례 상향).

## 정지 조건
- mAP_50 ≥ 0.72 달성
- 실험 4회 완료
- 최근 2회 연속 개선폭 < 0.005
- OOM 반복 발생

## Baseline

| 실험 | work_dir | 데이터 | epoch | mAP | mAP_50 | mAP_s | mAP_m | mAP_l |
|---|---|---|---|---|---|---|---|---|
| baseline (LOCO 단독) | rtmdet_s_lr1e4 | LOCO only | 100 | 0.278 | 0.596 | 0.095 | 0.266 | 0.477 |

## 실험 이력

| # | 실험 | work_dir | 변경점 | 데이터 | 상태 | epoch | mAP | mAP_50 | mAP_s | mAP_m | mAP_l | vs baseline mAP_50 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | loco+cardboard | rtmdet_s_loco_cardboard | LOCO+Cardboard 데이터 추가 (logistics 제외), lr=1e-4, RTMDet-s, 100ep | LOCO+Cardboard | **완료** (2026-07-21 18:06→23:47) | 100 (best@99) | 0.386 | 0.680 | 0.101 | 0.283 | 0.576 | **+0.084** (미달, 목표 0.72) |
| 2 | RTMDet-m 확대 | rtmdet_m_loco_cardboard | RTMDet-s→m, batch32→16, lr 1e-4→5e-5(배치비례), 나머지 실험1과 동일(100ep) | LOCO+Cardboard | **완료** (2026-07-21 23:52→2026-07-22 08:11) | 100 (best@100) | 0.408 | 0.710 | 0.107 | 0.307 | 0.597 | **+0.114** (미달, 목표까지 0.01) |
| 3 | 입력 해상도 ↑ | rtmdet_m_hires_loco_cardboard | RTMDet-m 유지(실험2가 최고 성능), 해상도 640→800, batch16→8(메모리), lr 5e-5→2.5e-5(배치비례), 나머지 동일(100ep) | LOCO+Cardboard | **조기 중단** (ep10에서 사용자 지시로 중단 — Jetson 10FPS 제약과 상충, 개선폭 미미) | 10 (중단 시점) | 0.310 | 0.581 | 0.067 | 0.204 | 0.475 | 참고용(미완주, 비교 불가) |
| 4 | 해상도 800 재개 (스테이션PC 이관) | rtmdet_m_800_loco_cardboard | 실험3 재개, batch8→20(새 메모리정책), lr2.5e-5→6.25e-5(배치비례), 나머지 실험2와 동일(100ep) | LOCO+Cardboard | **중단** (2026-07-22 12:43→16:05, 사용자 지시로 SCD 추가 combined 단일 run으로 전환) | 26 (중단 시점, best@25) | 0.360 | 0.662 | 0.095 | 0.262 | 0.528 | 참고용(미완주, 비교 불가) — ep25 기준 실험2 동일 시점(ep25 mAP_50 0.634)보다 근소 우위, OOM·붕괴 없이 정상 궤적이었음 |
| 5 | SCD 추가 combined | rtmdet_m_800_scd | 해상도 800 유지, train에 SCD(box 단일클래스, 6735장) 병합(box_coco_train_scd.json, 17236장), lr=1e-4(고정, 사용자 지정), batch20, 나머지 실험4와 동일(100ep). val은 기존 box_coco_val.json 그대로(공정 비교 고정) | LOCO+Cardboard+SCD(train만) | **완료** (2026-07-22 16:06→2026-07-23 07:51) | 100 (best@99) | 0.436 | **0.753** | 0.141 | 0.343 | 0.609 | **+0.157**(목표 0.72 달성) |

## 현재 최고 성능
실험5(RTMDet-m, 800해상도, SCD 추가, `rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth`): mAP_50 **0.753** (box 0.827 / pallet 0.679) — 목표(0.72) 달성. 참고로 SCD 추가 전 640해상도 실험2는 mAP_50 0.710.

## 전략 전환 (2026-07-22, 사용자 지시)
우선순위 (a)(b)(c)(d) 순차 확대 대신, 진단 기반 스크리닝 체계로 전환:
1. 실험2 best 체크포인트 classwise 진단(box/pallet 개별 AP, 오탐 유형)
2. train 25~30% 서브셋(소스 비율 유지) + val 전체 2,398장 고정으로 저비용 스크리닝(40ep, stage2는 마지막 4ep)
3. 진단이 시사하는 후보 2~3개(예: copy-paste 증강, mosaic 강도 조정) 스크리닝 — **서브셋 결과는 순위 비교용, 절대값으로 목표 판단 금지**
4. 스크리닝 승자만 전체 데이터 100ep 풀런으로 확정
5. epoch 연장·데이터 추가(logistics 등)는 서브셋으로 검증 불가 → 풀런 전용. logistics 추가는 사용자 업로드 필요 시 요청

## 진단: 실험2 classwise 평가 (2026-07-22)
`work_dirs/rtmdet_m_loco_cardboard/best_coco_bbox_mAP_epoch_100.pth`, val 전체 2,398장 기준.

**Classwise AP**

| class | mAP | mAP_50 | mAP_75 | mAP_s | mAP_m | mAP_l |
|---|---|---|---|---|---|---|
| box | 0.532 | 0.798 | 0.540 | 0.100 | 0.329 | 0.719 |
| pallet | 0.284 | 0.623 | 0.210 | 0.114 | 0.285 | 0.475 |

**오탐/미탐 분석** (score≥0.3, IoU≥0.5 매칭)

| class | TP | FP(클래스혼동) | FP(배경) | FN | precision | recall |
|---|---|---|---|---|---|---|
| box | 5732 | 7 | 2776 | 1466 | 0.673 | 0.796 |
| pallet | 13481 | 12 | 10084 | 5355 | 0.572 | 0.716 |

**핵심 발견**: 박스↔파렛트 클래스 혼동은 거의 없음(둘 다 <20건) — 오분류 문제 아님. **파렛트가
배경 오탐(10,084건)·미탐(5,355건) 모두 압도적으로 많고, mAP_75가 박스의 절반 이하(0.21 vs 0.54)로
로컬라이제이션도 헐겁다.** 데이터 비중은 파렛트가 71%로 다수임에도 성능은 더 나쁨 — 단순 클래스
불균형이 아니라 파렛트 자체의 시각적 난이도(형태 다양성/적재 상태/가림) 문제로 판단.

## 스크리닝 후보 (진단 근거, 작성만 하고 미실행)
config·스모크 테스트까지 완료(`configs/rtmdet_m_screen_{base,mosaic,copypaste,bboxloss}.py`,
서브셋 `scripts/make_train_subset.py`). 아래 순서로 실행할 계획이었으나 사용자 판단으로 착수 전 중단.
1. **mosaic 완화** — CachedMosaic prob 1.0→0.5, RandomResize ratio_range (0.1,2.0)→(0.3,1.7). 큰 객체(파렛트)가
   모자이크로 조각나 컨텍스트가 손실되는 것이 배경 오탐·미탐 증가의 원인일 수 있다는 가설.
2. **bbox 기반 copy-paste 증강** — 세그멘테이션 마스크가 없어(annotation에 segmentation 필드 없음) 표준
   mmdet CopyPaste(마스크 필요) 대신, bbox 크롭을 다른 이미지에 합성하는 커스텀 변환(`src/rtmdet_ext/copy_paste.py`)으로
   파렛트 등장 맥락 다양성을 늘려 배경 오탐 감소·가림 강인성(재현율)을 노림.
3. **loss_bbox 가중치 상향** — GIoU loss weight 2.0→4.0. 파렛트 mAP_75가 유독 낮은(로컬라이제이션 문제)
   현상을 직접 겨냥, augmentation과 독립적인 변수로 병행 스크리닝.

## ONNX Export (2026-07-22)
`pip install mmdeploy`(torch/mmcv/mmdet 버전 불변, dry-run으로 사전 확인 후 설치)로 실험2 체크포인트를
`mmdeploy.apis.torch2onnx`를 통해 변환. deploy config는 mmdeploy 저장소(v1.3.1, pip 배포판에는
미포함)에서 `detection_onnxruntime_dynamic.py` 계열만 받아옴. 결과: `end2end.onnx`(109MB, opset11,
input `[batch,3,height,width]` 동적축, output `dets`/`labels`). `onnx.checker`로 구조 검증 통과,
`onnxruntime`(CPU) 추론도 실행해 실제 이미지에서 합리적인 pallet 탐지 결과(label=1, score 0.3~0.585)
확인 — end-to-end로 정상 동작. TensorRT 엔진 빌드는 Jetson 대상 하드웨어에서 진행 필요(이 세션 범위 밖).

## ONNX 저성능 진단 (2026-07-22, 스테이션 PC 리포트 대응)

**증상**: 스테이션 PC에서 `end2end.onnx`(실험2 -m@640)를 onnxruntime으로 추론하면 학습 도메인
(LOCO) 이미지에서도 최고 score가 0.5~0.57에 그침 — mAP_50 0.710짜리 모델치고 비정상적으로 낮음.
정규화 이중처리 시 0.11로 붕괴(→ 정규화 1회가 맞다는 정황), 원시 픽셀 0.42, input size 640~1280
전부 시도해도 0.5대 정체.

**진단 방법**: 동일 LOCO 이미지 1장(`data/processed/staged_images/loco/subset-4/2020-01-16_17/
cam2/1579163807.9130263.jpg`)에 대해 (a) mmdet `inference_detector`로 원본 .pth(CPU) 추론과
(b) 동일 텐서를 `onnxruntime`에 직접 입력한 결과를 직접 대조. GPU 미사용, 진행 중인 실험4
학습(GPU1)과 무관하게 CPU만 사용.

**ONNX 그래프 구조 확인**: `onnx.checker`로 노드를 순회한 결과 입력(`input`) 바로 뒤에 정규화
연산(Sub/Div)이 없고 곧바로 backbone Conv가 이어짐 — 즉 **정규화가 그래프에 내장되어 있지 않고,
호출자가 정규화까지 마친 텐서를 넣어야 하는 구조**(mean=[103.53,116.28,123.675],
std=[57.375,57.12,58.395], BGR 유지·RGB 변환 안 함 — `configs/rtmdet_m_forklift.py`의
`data_preprocessor`와 동일). 이 부분은 스테이션 PC 쪽 판단이 맞았음.

**핵심 발견 — export는 정상, 원인은 전처리의 리사이즈 방식**:
- mmdet `test_pipeline`은 `Resize(scale=(640,640), keep_ratio=True)` + `Pad(size=(640,640),
  pad_val=114)` 조합 — 종횡비를 유지한 채 긴 변 기준으로 축소한 뒤 남는 영역을 114로 패딩한다
  (예: 1280×800 원본 → 0.5배 축소해 640×400 → 우측/하단을 114로 패딩해 640×640).
- 이 정확한 파이프라인으로 만든 텐서를 onnxruntime에 넣으면 top score **0.6496**으로 torch
  오라클(`inference_detector`)의 0.6496과 **완전히 일치**(바운딩박스도 scale_factor=0.5 배율
  관계로 정확히 일치, 예: torch box 547.97 ≒ onnx box 273.98×2).
- 반면 정사각형으로 단순 리사이즈(`cv2.resize(img,(size,size))`, 종횡비 무시)한 텐서를 넣으면
  top score가 **0.5848로 하락** — 스테이션 PC에서 보고한 0.5~0.57대와 정확히 일치하는 수치.
  input size를 640→1280으로 올려도 종횡비 왜곡 자체는 그대로이므로 점수가 안 오른 것도 설명됨.
- **결론: ONNX export는 손상되지 않았다.** 재export 불필요. 원인은 스테이션 PC 추론 스크립트가
  mmdet의 keep_ratio Resize+Pad 대신 단순 정사각형 리사이즈를 쓰고 있었던 것.

**스테이션 PC에 필요한 정확한 전처리 레시피**:
1. `scale = min(target/W, target/H)` (target=640, W·H=원본 이미지 너비·높이)
2. `new_w, new_h = round(W*scale), round(H*scale)`로 종횡비 유지한 채 리사이즈
3. target×target 캔버스를 114(전 채널 동일)로 채우고 리사이즈된 이미지를 좌상단 (0,0)에 배치
4. BGR 유지(RGB 변환 금지), CHW로 변환
5. 채널별 정규화: `(pixel - mean) / std`, mean=[103.53,116.28,123.675], std=[57.375,57.12,58.395]
   (BGR 채널 순서 그대로 적용)
6. 배치 차원 추가 후 onnxruntime 입력
7. 출력 `dets`의 박스 좌표는 패딩된 640×640 좌표계이므로, 원본 이미지 좌표로 되돌리려면
   `bbox_orig = bbox_model / scale`로 나눠야 함(위 예시는 `scale=0.5`)

**".pth 직접 추론(mmdet)으로 전환" 실현성 검토(팀원 전지웅 RTMDet-tiny 사례 참고)**: 팀원이 같은
스테이션 PC에서 별도 모델(LSCD 학습 RTMDet-tiny)을 mmdet `.pth` 직접 추론으로 이미 운용 중이라는
것은, torch/mmcv/mmdet/mmengine 추론 스택이 그 PC에 **이미 설치·검증되어 있다는 뜻**이다. 즉
우리 -m 체크포인트도 config+checkpoint만 옮기면 추가 패키지 설치 없이 바로 `.pth` 직접 추론이
가능할 가능성이 높다(버전 호환 확인만 하면 됨).
- **의견**: 지금은 재export가 필요 없고 ONNX export 자체가 정상으로 확인됐으므로, 당장 전환을
  강제할 이유는 없다. ONNX는 런타임이 가볍고(무거운 mmdet/mmcv 스택 불필요) TensorRT 이관과도
  방향이 맞아 기본 경로로 유지 추천. 다만 위 전처리 레시피가 다소 손이 많이 가고 실수하기 쉬운
  지점(이번 사례처럼)이라, 팀원 사례로 보아 `.pth` 직접 추론 전환 비용이 이미 매우 낮다는 점을
  고려하면 **크로스체크용 폴백**(ONNX 결과가 의심스러울 때 대조 기준으로 사용)으로 mmdet 직접
  추론 경로를 station PC에 유지해두는 것을 권장. 강제 전면 전환은 불필요.

## 실험5: SCD 추가 combined 데이터 구성 (2026-07-22)

**SCD (`data/raw/coco_style_oneclass.zip`)**: COCO 스타일, category `Carton`(id=1) 단일 클래스,
train 6735장/70838 annotation, val 1000장/11032 annotation. 이미지가 매우 고해상도(최대
3968×3809 수준)이고 상자 밀집도가 높음(이미지당 평균 ~10.5개, LOCO+Cardboard보다 훨씬 조밀).
**라이선스 CC BY-NC-SA(비영리)** — 데모/연구용으로만 사용, 상업적 이용 금지.

**병합 절차**:
1. `scripts/extract_scd.py`로 압축 해제 → `data/processed/staged_images/scd/{train2017,val2017}/`,
   annotation은 `data/processed/scd_annotations/`에 원본 보관. 파일 존재·이미지 크기(json vs 실제)
   전수 대조는 샘플 확인, bbox 좌표계는 표준 COCO `[x,y,w,h]`로 우리 파이프라인과 호환 확인.
2. `scripts/make_scd_merge.py`로 category `Carton`(1) → 우리 스키마의 `box`(id=1)로 매핑(카테고리가
   단일임을 assert로 검증). pallet 카테고리 없음 확인(LOCO 계보만 유지, 오염 없음).
   기존 `box_coco_train.json`(10501장)에 이미지/annotation id를 이어붙여
   `box_coco_train_scd.json`(**17236장**, box 104601 / pallet 83758 annotation)으로 병합.
   **val은 건드리지 않음** — `box_coco_val.json`(2398장) 그대로 유지(채점표 고정).
   SCD val(1000장)은 별도로 `scd_val_coco.json`으로 리맵해 저장(학습에는 미사용, 완주 후 참고
   리포트용 — "가능하면" 조건부 과제이므로 학습 완료 후 시도).
3. 무작위 샘플(2000 annotation) bbox 경계 검증: 이미지 범위 밖/퇴화 bbox 2건뿐(무시 가능한 수준).

**실험5 config**(`configs/rtmdet_m_800_scd_forklift.py`, work_dir `rtmdet_m_800_scd`): 해상도 800
유지(실험4와 동일 파이프라인), train만 `box_coco_train_scd.json`으로 교체, lr=1e-4(사용자 지정
고정값 — 데이터가 대폭 늘어난 만큼 배치비례 대신 명시적으로 높게 설정), batch20(실험4에서
25.57GB로 메모리 검증된 값 재사용), COCO 프리트레인 백본에서 시작(`load_from` 유지), 100epoch.
스모크 테스트 통과(메모리 25.7GB, box/pallet classwise eval 정상 출력 확인).

## 실험5(800) ONNX Export — 스테이션 배포용 (2026-07-23, Jira 146)

**배경**: Jira 146 완료로 실험5 모델(mAP_50 0.753)을 측정 스테이션(4070 PC)에 배포하려는데
체크포인트 백업본에 `.pth`만 있고 ONNX가 없어 추가 요청됨.

**절차**: 640 모델 export 때 썼던 mmdeploy 레시피(`mmdeploy.apis.torch2onnx`, deploy config
`detection_onnxruntime_dynamic` 계열)를 그대로 재사용하고 `model_cfg`만 `configs/rtmdet_m_800_scd_forklift.py`,
체크포인트만 `best_coco_bbox_mAP_epoch_99.pth`로 교체(입력 해상도는 config에 이미 800으로 박혀있어
deploy config 자체는 무변경 — `onnx_config.input_shape=None` 동적축이라 model_cfg의 800 test_pipeline을
그대로 따라감). GPU1, `CUDA_VISIBLE_DEVICES=1`에서 실행.

**인터페이스 확인**: `onnx.checker` 통과, 그래프 노드 구조가 640 export와 동일 — `input`
바로 뒤에 정규화 연산 없이 backbone Conv로 직결(정규화는 여전히 호출자 책임), output은
`dets`(x1,y1,x2,y2,score)+`labels`로 640과 동일 인터페이스(스테이션 코드 무수정으로 호환).

**검증**: LOCO val 3장(무작위)에 대해 torch `.pth` 오라클(CPU)과 onnxruntime(CPU) 결과를
직접 대조 — **top-3 score가 소수점 4자리까지 완전히 일치**(예: 0.7571/0.4959/0.4944 양쪽 동일).
점수 붕괴 없음, export 정상.

**추론 속도 참고치**(스테이션 4070 아님 — 이 서버 기준 근사치, 실측은 스테이션에서 재확인 필요):
- onnxruntime CPU(이 서버, GPU provider 미설치): **94.0ms/frame**
- torch 전체 파이프라인(전처리+forward+NMS) L40S GPU1: **20.4ms/frame**
- 둘 다 스테이션 KPI(박스 도착→판정 ≤1초=1000ms) 대비 여유가 크다(최악值 CPU 94ms도 10배 이상 여유).
  단, L40S≠4070·PyTorch≠onnxruntime-GPU이므로 참고치일 뿐 — **스테이션 4070에서 onnxruntime-GPU로
  직접 재측정 권장**. 만약 실측이 1초에 근접하면 640 재export(이미 존재)로 폴백 가능.

**산출물**: `work_dirs/rtmdet_m_800_scd/onnx_export/end2end.onnx`(109MB, opset11). 기존 백업
tar에 추가해 `~/rtmdet_checkpoints_20260723.tar.gz`(450MB로 갱신) — `checkpoints/rtmdet_m_800_scd_end2end.onnx`.

## 로그
- 2026-07-21 18:25 — 루프 시작. 기존 nohup 학습(rtmdet_s_loco_cardboard, PID 1017342, GPU1)을 백그라운드 watcher로 추적 시작. 완주 대기 중.
- 2026-07-21 19:27 — 폴백 점검: epoch 24/100 진행 중, loss 안정적 감소(≈0.85), ETA ~23:20, GPU1 사용량 19.2GB(한도 20GB 내). 변경 없음, 계속 대기.
- 2026-07-21 20:28 — 폴백 점검: epoch 42/100 진행 중, ETA ~23:30. 중간 검증: ep30 mAP_50 0.609, ep35 mAP_50 0.608(best mAP 0.332), ep40 mAP_50 0.587(등락). GPU1 19.2GB, 정상. 계속 대기.
- 2026-07-21 21:29 — 폴백 점검: epoch 60/100 진행 중, ETA ~23:35. 중간 검증: ep45 mAP_50 0.624(best mAP 0.339), ep50 mAP_50 0.618, ep55 mAP_50 0.616. GPU1 19.2GB, 정상. 계속 대기.
- 2026-07-21 22:29 — 폴백 점검: epoch 78/100 진행 중, ETA ~23:40(잔여 ~1h10m). 중간 검증 상승 추세: ep60 mAP_50 0.636(best mAP 0.348), ep65 0.636(best 0.349), ep70 0.648(best 0.353), ep75 0.640. 이미 baseline(0.596) 상회. GPU1 19.2GB, 정상. 계속 대기.
- 2026-07-21 23:29 — 폴백 점검: epoch 94/100, 잔여 ~15분. epoch 90에서 PipelineSwitchHook(약증강 전환) 발동 후 mAP_50 급상승: ep90 0.657, ep91 0.672, ep93 0.678(best mAP 0.382), ep94 0.678. 목표(0.72) 근접했으나 아직 미달. 곧 완주 예상, 짧은 폴백으로 재점검.
- 2026-07-21 23:49 — **실험 1 완료**: epoch100 mAP 0.384/mAP_50 0.680/mAP_s 0.101/mAP_m 0.284/mAP_l 0.573 (best epoch99: mAP 0.386/mAP_50 0.680, `best_coco_bbox_mAP_epoch_99.pth`). baseline 대비 mAP_50 +0.084, mAP_s +0.006. 목표(0.72) 미달, 정지조건 미해당(실험 1회차) → 실험 2 진행.
- 2026-07-21 23:52 — 실험 2 config 작성(configs/rtmdet_m_forklift.py, 커밋 bd63584) 및 스모크 테스트 통과(메모리 13.3GB). RTMDet-m 풀 학습 기동: PID 1023425, GPU1, work_dirs/rtmdet_m_loco_cardboard, batch16/lr5e-5/100ep. 완료 감지용 백그라운드 watcher 재설정.
- 2026-07-21 23:53 — 기동 확인: epoch1 정상 진행, GPU1 합산 18.5GB(한도 20GB 내지만 여유 ~1.5GB로 빠듯 — 향후 OOM 발생 시 즉시 배치 축소 재시도 예정). batch16→iteration 수 2배(657/epoch)로 ETA 약 7:57, 완주 예상 2026-07-22 ~07:50.
- 2026-07-22 00:24 — 30분 조기 점검: epoch 7/100, GPU1 18.5GB로 안정(OOM 없음), loss 순조롭게 감소. 이후 평소 1시간 주기로 복귀. ETA ~07:40.
- 2026-07-22 01:25 — 폴백 점검: epoch 19/100, GPU1 18.5GB 안정. 중간 검증: ep5 mAP 0.269, ep10 mAP_50 0.569, ep15 mAP_50 0.594(실험1 동일 시점과 유사한 궤적). ETA ~6h20m. 계속 대기.
- 2026-07-22 02:26 — 폴백 점검: epoch 32/100, GPU1 18.5GB 안정. 중간 검증: ep20 mAP 0.343, ep25 mAP_50 0.634, ep30 mAP_50 0.642(실험1 동일 시점 0.609보다 소폭 우위). ETA ~5h22m. 계속 대기.
- 2026-07-22 03:27 — 폴백 점검: epoch 44/100, GPU1 18.5GB 안정. 중간 검증: ep35 mAP_50 0.647, ep40 mAP_50 0.651(best mAP 0.364). ETA ~4h23m. 계속 대기.
- 2026-07-22 04:28 — 폴백 점검: epoch 56/100, GPU1 18.5GB 안정. 중간 검증: ep45 mAP_50 (best mAP 0.368), ep50 mAP_50 0.662, ep55 mAP_50 0.663(best mAP 0.371). ETA ~3h26m. 계속 대기.
- 2026-07-22 05:29 — 폴백 점검: epoch 69/100, GPU1 18.5GB 안정. 중간 검증: ep60 mAP_50 0.674(best mAP 0.380), ep65 mAP_50 0.675. 실험1 동일 시점(ep65 0.636)보다 우위 지속. ETA ~2h28m. 계속 대기.
- 2026-07-22 06:29 — 폴백 점검: epoch 81/100, GPU1 18.5GB 안정. 중간 검증: ep70 mAP_50 0.680(best mAP 0.383), ep75 mAP_50 0.679, ep80 mAP_50 0.688(best mAP 0.386). ETA ~1h31m. 실험1처럼 ep90 stage2 전환 시 추가 상승 기대 → 짧은 폴백으로 재점검.
- 2026-07-22 06:59 — 폴백 점검: epoch 87/100, GPU1 18.5GB 안정. ep85 mAP_50 0.687(best mAP 0.388). ETA ~1h. stage2 전환(ep90) 임박 → 짧은 폴백 유지.
- 2026-07-22 07:25 — 폴백 점검: epoch 92/100. stage2 전환(ep90) 후 mAP_50 급상승: ep89 0.689, ep90 0.689(best mAP 0.392), ep91 **0.698**(best mAP 0.397). 목표(0.72) 근접. ETA ~38분, 짧은 폴백 유지.
- 2026-07-22 07:51 — epoch 97/100, ETA ~18분. mAP_50 계속 상승: ep93 0.700, ep94 0.707, ep95 0.707, ep96 **0.707**(best mAP 0.406). 목표(0.72)에 근접했으나 아직 미달.
  **GPU1 합산 21.3GB로 20GB 가이드라인 초과 감지** — 원인 확인: 내 학습 프로세스(PID 1023554)는 15.4GB로 안정적(증가 없음), 초과분은 Isaac Sim 프로세스(PID 1029633, 팀원)가 자체적으로 ~3GB→5.9GB로 늘어난 것. 물리 GPU는 46GB 중 21GB만 사용, 양쪽 프로세스 모두 정상 동작(OOM 없음), 팀원 프로세스는 규칙상 건드릴 수 없음. 완주까지 18분 남아 지금 중단 시 97/100 epoch 진행분 손실 → 킬하지 않고 짧은 간격으로 계속 감시하기로 판단.
- 2026-07-22 08:11 — **실험 2 완료**: epoch100 mAP 0.408/mAP_50 **0.710**/mAP_s 0.107/mAP_m 0.307/mAP_l 0.597(best epoch100, `best_coco_bbox_mAP_epoch_100.pth`). baseline 대비 +0.114, 실험1 대비 +0.030. 목표(0.72)까지 0.01 차이로 미달. 학습 종료 후 GPU1 6.1GB로 정상 복귀(Isaac Sim만 남음, 위 메모리 경보는 팀원 프로세스 요인으로 최종 확인). 정지조건 미해당(실험 2회차, 직전 개선폭 0.030≥0.005) → 실험 3 진행.
- 2026-07-22 08:14 — 실험 3 config 작성(configs/rtmdet_m_hires_forklift.py, 커밋 d37bc79, 해상도 640→800·batch16→8·lr2.5e-5, RTMDet-m 유지) 및 스모크 테스트 통과(메모리 10.7GB, 여유 충분). 풀 학습 기동: PID 1032894, GPU1, work_dirs/rtmdet_m_hires_loco_cardboard.
- 2026-07-22 08:15 — 기동 확인: epoch1 정상, GPU1 18.3GB(안전). 배치8·해상도800으로 iteration 수 대폭 증가(1313/epoch) → **ETA 약 13h, 완주 예상 오늘 21:20경**(실험2 대비 훨씬 오래 걸림). 완료 감지 watcher 설정, 중간 지표 보며 계속 모니터링.
- 2026-07-22 09:17 — 폴백 점검: epoch 8/100, GPU1 18.5GB 안정. ep5 mAP 0.272/mAP_50 0.519(실험2 ep5 mAP 0.269와 유사한 궤적). ETA ~11h49m(완주 ~21:05). 계속 대기.
- 2026-07-22 09:42 (사용자 지시) — **실험3 조기 중단**: 사용자 판단으로 `pkill -f rtmdet_m_hires` 실행(학습 프로세스만, Isaac Sim·기타 프로세스 무영향 확인, GPU1 5.9GB로 복귀). 사유: 해상도 800은 Jetson 배포 목표(~10FPS 제약)와 충돌하는 방향이었고, ep5→ep10 개선폭도 미미(mAP_50 0.519→0.581, mAP 기준 +0.038이나 실질 유효 개선 미미로 판단, +0.012 수준). 완료 감지 watcher도 함께 정리됨(같은 패턴 매칭으로 동반 종료 확인).
  전략 전환: (1) 실험2(RTMDet-m@640, mAP_50 0.710) 체크포인트로 classwise 진단 우선 → (2) train 25~30% 서브셋 스크리닝 체계로 후보를 저비용 비교 → (3) 승자만 전체 데이터 100ep 풀런으로 확정. val은 전체 2,398장 유지(스크리닝도 고정 채점표로 평가).
- 2026-07-22 09:43~09:53 — classwise 진단 실행 및 스크리닝 체계 구축: 실험2 체크포인트 classwise 평가(box 0.798/pallet 0.623 mAP_50), 오탐/미탐 분석(클래스 혼동 없음, 파렛트 배경오탐·미탐 압도적). train 27% 서브셋 생성(`scripts/make_train_subset.py`, seed=42, val 불변). 스크리닝 config 4종(대조군+후보3) 작성·커밋, 대조군·mosaic 후보 스모크 테스트 통과.
- 2026-07-22 09:56 (사용자 지시) — **공개 데이터 튜닝 루프 마감**: 사용자가 진단을 재해석 — 파렛트 약점은 LOCO 특유의 밀집·원거리 장면 문제이며 데모 시나리오(근접 단일 파렛트)와 무관하다고 판단, 서브셋 스크리닝(copy-paste 스모크 테스트 도중) 중단 지시. 실험2를 이 단계 최종 모델로 확정.
- 2026-07-22 10:00 — mmdeploy 설치(dry-run으로 기존 torch/mmcv/mmdet 버전 불변 확인 후 진행) 및 실험2 체크포인트 ONNX export 성공. `onnx.checker`·`onnxruntime` 추론으로 검증 완료(위 "ONNX Export" 절 참고). GPU1 프로세스 정리 완료(Isaac Sim만 남음), 루프 정지.
- 2026-07-22 12:42 (사용자 지시) — **실험4 착수**: 아키텍처 변경(측정 추론 Jetson→스테이션 PC 이관, MR !36)으로 실험3 중단 사유(Jetson 10FPS 제약) 소멸 → 해상도 800 재개. config 작성(`configs/rtmdet_m_800_forklift.py`, 커밋 b5f7bd8), batch20/lr6.25e-5(새 메모리정책 batch16→24 범위 내). 스모크 테스트 통과(메모리 25.57GB — Isaac Sim 8.5GB와 합산 시 40GB 한도 내 여유 있음).
- 2026-07-22 12:43 — 풀 학습 기동: PID 1049099, GPU1, `work_dirs/rtmdet_m_800_loco_cardboard`. 목적은 mAP 경쟁이 아닌 해상도800 베이스 확보이므로 완주만 확인하고 실험2(@640)와 비교 기록 예정. 완료 감지 watcher 설정, 폴백 점검 계속.
- 2026-07-22 (실험4 학습과 병행, GPU 미사용 CPU 진단) — **ONNX 저성능 원인 진단**: 스테이션 PC onnxruntime 추론 점수(0.5~0.57)가 비정상적으로 낮다는 리포트에 대응, 동일 이미지로 torch .pth(CPU) vs onnxruntime 직접 대조. 정확한 mmdet 전처리(keep_ratio Resize+Pad)로 만든 텐서를 넣으면 onnx 결과가 torch 오라클과 완전히 일치(top score 0.6496=0.6496, 박스도 scale_factor 배율로 정확히 일치) — **export는 정상**. 단순 정사각형 리사이즈(종횡비 무시)를 쓰면 score가 0.5848로 하락, 스테이션 PC가 보고한 수치대와 일치 — 원인은 스테이션 PC 전처리 스크립트의 리사이즈 방식(원인이 client-side이므로 재export 불필요). 정확한 전처리 레시피와 `.pth` 직접 추론 전환 실현성 검토 결과를 위 "ONNX 저성능 진단" 절에 기록.

- 2026-07-22 16:05 (사용자 지시) — **실험4 중단, SCD 추가 combined 전환**: epoch26 시점(best@25: mAP 0.360/mAP_50 0.662)에서 `pkill -f rtmdet_m_800`로 학습 프로세스만 종료(Isaac Sim 무영향 확인, GPU1 6.7GB로 복귀). 실험 이력에 중단 시점 기록.
- 2026-07-22 16:05~16:06 — SCD 데이터 준비: `data/raw/coco_style_oneclass.zip`(16.1GB, CC BY-NC-SA 비영리) 압축 해제(`scripts/extract_scd.py`, train 6735장/val 1000장 전량 확인) → category `Carton`→`box` 매핑, id 오프셋 처리해 `box_coco_train_scd.json`(17236장) 병합(`scripts/make_scd_merge.py`). val은 기존 `box_coco_val.json` 불변. pallet 카테고리 없음/파일 존재/이미지 크기/bbox 경계 샘플 검증 통과. 코드(스크립트 2종+config) 커밋(3e8457e).
- 2026-07-22 16:06 — 실험5 config 작성(`configs/rtmdet_m_800_scd_forklift.py`, work_dir `rtmdet_m_800_scd`, 해상도800/batch20/lr1e-4 고정/COCO 프리트레인), 스모크 테스트 통과(메모리 25.7GB, box/pallet classwise eval 정상). 풀 학습 기동: PID 1084643, GPU1. 완료 감지 watcher 설정, 초기 몇 epoch 붕괴 여부 조기 확인 예정.
- 2026-07-22 17:12 — **실험5 epoch5 조기 점검(붕괴 여부 확인)**: 전체 mAP 0.280/mAP_50 0.531/mAP_75 0.246, classwise box mAP_50 0.624/pallet mAP_50 0.437. 실험4(SCD 추가 전) 동일 시점(ep5 mAP_50 0.532)과 거의 동일 — **SCD 대량 추가(17236장, +64%)에도 초기 학습 붕괴·이상 징후 없음**. loss 2.5→0.55로 정상 하강. GPU 29.5GB+Isaac Sim 8.0GB=37.5GB로 안정. 로그에 `Corrupt JPEG data: premature end of data segment` 경고 1건(SCD 이미지 중 손상 파일 추정) — 학습에는 영향 없이 정상 진행 중, 치명적이지 않아 계속 관찰만.
- 2026-07-22 18:09 — **실험5 epoch10 조기 점검**: 전체 mAP 0.318/mAP_50 0.603/mAP_75 0.279, classwise box mAP_50 0.690/pallet mAP_50 0.516 — epoch5 대비 계속 상승, 붕괴 없음. 같은 epoch10 시점 비교: 실험2(@640, mAP_50 0.569)·실험4(@800, SCD 추가 전, mAP_50 0.590)보다 실험5(SCD 추가, mAP_50 0.603)가 오히려 근소 우위 — SCD 대량 추가가 전체 성능을 저해하지 않고 있음. 초기 붕괴 점검(ep5·ep10) 완료, 이후 표준 1시간 폴백 점검으로 전환.
- 2026-07-23 06:23 — **실험5 epoch90: 목표 mAP_50 0.72 달성**(0.724), stage2 약증강 전환(PipelineSwitchHook) 발동. classwise box precision 0.525/pallet 0.285. 남은 10epoch 완주 대기, ETA ~1.5h.
- 2026-07-23 07:51 — **실험5 완료**: epoch100 mAP 0.435/mAP_50 0.753/mAP_s 0.141/mAP_m 0.343/mAP_l 0.609 (best epoch99: mAP 0.436/mAP_50 0.753, `best_coco_bbox_mAP_epoch_99.pth`). classwise box mAP_50 0.827/pallet mAP_50 0.679. 목표(mAP_50≥0.72) 달성(+0.033), 실험2(@640, 0.710) 대비 +0.043. stage2 전환(ep90, mAP_50 0.724) 이후 크게 상승(ep93 0.746→ep96 0.753)한 패턴은 기존 실험들과 동일. GPU1 학습 프로세스 정상 종료, 완전히 유휴 상태로 복귀. "최종 산출물" 표를 실험5 기준으로 갱신(800 해상도 모델 = 실험5, 실험4는 중단되어 대체됨).
- 2026-07-23 07:55 — **체크포인트 백업**: 최종 3종(-m@800 실험5 best/-m@640 실험2 best/-s 실험1 best) + ONNX(640) + 대응 config 3종 + README를 `~/rtmdet_checkpoints_20260723.tar.gz`(348MB)로 압축, 홈 디렉터리에 저장. tar 내용 검증 완료(`tar -tzf`). 경로: `/home/j-i15a304/rtmdet_checkpoints_20260723.tar.gz`. GPU1 완전 유휴 확인, 루프 정지·대기.
- 2026-07-23 09:29~09:31 (Jira 146) — **실험5(800) ONNX export**: 640 레시피 재사용, model_cfg/checkpoint만 800 모델로 교체해 `work_dirs/rtmdet_m_800_scd/onnx_export/end2end.onnx` 생성. onnx.checker 통과, LOCO val 3장에서 torch 오라클과 top-3 score 완전 일치 확인(점수 붕괴 없음). 추론 속도 참고치(이 서버 기준): onnxruntime CPU 94ms/frame, torch L40S GPU 20.4ms/frame — 1초 KPI 대비 여유 큼(스테이션 4070 실측 권장). 백업 tar에 추가해 `~/rtmdet_checkpoints_20260723.tar.gz`(450MB) 갱신.
- 2026-07-23 16:36 — **파인튜닝/평가 파이프라인 스테이징 완료**(학습 미실행). `tools/eval_domain.py`(measure-first 평가 하네스, 기존 val에서 mAP@0.5 0.753 재현 검증됨), `tools/prelabel.py`(CVAT용 프리라벨, 샘플 3장 동작 확인), `configs/finetune/{rig_m800,mini_s640}.py`(각각 -m@800/-s@640 파인튜닝용, 현재 최고 체크포인트에서 load_from, lr=1e-4 고정, 25ep, save_best=coco/bbox_mAP_50, 데이터 경로는 TODO 플레이스홀더, 둘 다 기존 데이터로 스모크 통과) 작성. 상세 절차는 `ai/PIPELINE_STAGING.md` 참고. 커밋은 스테이징 브랜치로 분리.

## 실험6: 리그 실물 도메인 파인튜닝 (2026-07-24, Jira 156)

**배경**: 실험5 모델(mAP_50 0.753)이 스테이션 측정 PC 실물 검증에서 **검은 플라스틱 파렛트를
전혀 못 잡음** — score 임계 0.02까지 낮춰도 파렛트 후보 0건, 같은 프레임 박스는 0.91~0.92로 정상
탐지. 원인은 목재 파렛트 위주 LOCO 도메인과 회색 카펫 위 검은 플라스틱 파렛트의 도메인 갭.
편하중 판정(FR-104)이 파렛트를 기준점으로 요구해 이게 막히면 MR 병합이 막힘.

**데이터**: 자체 촬영 rig 289장(1920×1080, 스테이션 BRIO 100 카메라) `data/raw/rig/20260724/`,
`data/processed/rig_labeled.json`(COCO, box=1/pallet=2, box 871·pallet 273). 검증: 289장 전수 파일
존재·이미지 크기(전부 1920×1080)·id 유일성·orphan 0 통과. **경계 밖 bbox 12건**(box 10건 y≈-0.1~
-0.7, pallet 2건 하단 6~9px overflow)은 `scripts/make_rig_merge.py`에서 이미지 경계로 클리핑
(퇴화 제거 0건). **네거티브(파렛트 없는 프레임)**: 파일 0121~0128 8장이 진짜 empty-GT(0 annotation),
0129~0136 8장은 box만 있고 pallet 없음(= 파렛트-free지만 empty는 아님). 반입 노트의 "네거티브 16장"
중 학습에서 실제로 empty-GT로 취급되는 것은 8장.

**병합**(`scripts/make_rig_merge.py`, `box_coco_train_scd_rig.json` = 17525장/189503 ann):
| source | images | box | pallet |
|---|---|---|---|
| SCD | 6735 | 70838 | 0 |
| roboflow_cardboard(train) | 6198 | 12279 | 0 |
| LOCO | 3419 | 19582 | 83758 |
| roboflow_cardboard_valid | 573 | 1240 | 0 |
| roboflow_cardboard_test | 311 | 662 | 0 |
| **rig** | **289** | **871** | **273** |
| **TOTAL** | **17525** | **105472** | **84031** |

rig는 이미지 1.65%·annotation 0.60%·전체 pallet 중 0.32%. rig 289장은 한 세션 한 배치 촬영이라
val을 떼면 누수 → **전량 train**. val은 기존 `box_coco_val.json`(2398장) 그대로(채점표 고정, 회귀 감시).
2단 순차 파인튜닝은 실험4 pallet forgetting으로 폐기됐으므로 실험5식 **단일 combined run**.

**config**(`configs/rtmdet_m_800_rig_forklift.py`, work_dir `exp6_rig`): 실험5 config 복사 후 수정 —
load_from을 실험5 best(`rtmdet_m_800_scd/best_coco_bbox_mAP_epoch_99.pth`)로 교체(COCO 프리트레인
아님, 도메인 적응), train ann을 `box_coco_train_scd_rig.json`으로, **`filter_cfg=dict(filter_empty_gt=
False)`** 추가(base 기본값 True를 덮어써 rig 네거티브 8장 보존 — 빌드 검증: filter=False 17525장 vs
filter=True 17515장, 정확히 empty-GT 10장(rig 8+SCD 2) 차이), lr=1e-4 고정, batch16(보수적 — GPU1
Isaac Sim 상주), img_size 800, max_epochs 50/val_interval 1/stage2 마지막 10ep(ep40 PipelineSwitchHook),
save_best=`coco/bbox_mAP_50`. 스모크(200장 1ep) 통과: 체크포인트 로드·classwise box/pallet eval·
학습 메모리 20.6GB 확인.

- 2026-07-24 13:01 — **실험6 풀 학습 기동**: PID 1152942, GPU1, `work_dirs/exp6_rig`, 로그
  `work_dirs/exp6_rig/train_20260724_130109.log`. 학습 메모리 24.4GB + Isaac Sim 7.2GB = 31.5GB/46GB
  (안전). 워밍업 loss ~0.47(warm-start라 실험5 cold-start 2.5보다 훨씬 낮게 시작). ETA ~8h(1096
  iter/epoch × 50ep, 완주 예상 ~21:00). `Corrupt JPEG data` 경고 1건은 기존 SCD 손상 파일(실험5에서
  기록됨, 비치명적). 완료 감지 모니터 설정, val 곡선 보며 조기 중단 판단 예정.
- 2026-07-24 21:19 — **실험6 완료**: 50epoch 완주, PID 정상 종료(GPU1 유휴 복귀, Isaac Sim 무영향).
  val 곡선(전체 mAP_50)은 stage1(ep1~39) 0.70~0.716 밴드 안정 진동 → **ep40 stage2 약증강 전환 후
  급상승**: ep40 0.731 → ep41 0.752 → ep46 0.764 → **ep49 0.765(best)** → ep50 0.762. box·pallet가
  전 구간 동반 이동 — pallet catastrophic forgetting(실험4 폐기 사유) 재발 없음.
  **best `work_dirs/exp6_rig/best_coco_bbox_mAP_50_epoch_49.pth`**:

  | 지표 | 실험6 ep49 | 실험5 | 증감 |
  |---|---|---|---|
  | mAP_50 | **0.765** | 0.753 | +0.012 |
  | box mAP_50 | **0.832** | 0.827 | +0.005 |
  | pallet mAP_50 | **0.692** | 0.679 | +0.013 |

  box 회귀 없음(오히려 +0.005) → 혼합 비율 재조정 불필요. 판정 게이트 통과.
- 2026-07-25 — **실험6 rig 실추론 판정(핵심)**: rig 파렛트 프레임 12장(세션 등간격 샘플)에서 실험5 vs
  실험6 직접 대조(`scripts/infer_rig_compare.py`, CUDA_VISIBLE_DEVICES=1). **실험5: 파렛트 score>=0.5
  검출 0/12**(max score 0.037~0.112 — 스테이션이 보고한 "0.02까지 낮춰도 0건"과 정확히 일치).
  **실험6: 11/12**(max score 평균 0.848, 범위 0.83~0.92). 유일한 미달 프레임 0013은 0.492로 임계 바로
  아래인데, 이 프레임은 파렛트 bbox가 하단 경계를 6~9px 넘어 잘린(truncated) 케이스 — 검출은 되나
  점수만 0.5 미만. 검은 플라스틱 파렛트 도메인 갭 해소 확인.
- 2026-07-25 16:05 — **실험6 ONNX export**: 실험5와 동일 mmdeploy 레시피(`torch2onnx`, deploy config
  `tools/mmdeploy_configs/mmdet/detection/detection_onnxruntime_dynamic.py` = 이전 세션 scratchpad에서
  레포로 이동 보존, opset11 동적축), model_cfg/checkpoint만 실험6으로 교체. 산출물
  **`work_dirs/exp6_rig/onnx_export/end2end.onnx`**(109MB). 검증: onnx.checker 통과, 인터페이스
  `input[batch,3,h,w]` 동적 → `dets`+`labels`(실험5와 동일 = 스테이션 코드 무수정 호환), 입력 직후
  정규화 노드 없이 Conv 직결(정규화 여전히 호출자 책임, 레시피 불변). rig 3장(검은 파렛트 포함)에서
  torch 오라클(CPU) vs onnxruntime(CPU) **top-3 score 소수 4자리 완전 일치**(파렛트 score 포함:
  0.8978/0.9146/0.4926 양쪽 동일). export 정상.
- 2026-07-25 16:07 — **실험6 백업**: config+ep49 pth+onnx+README를 `~/rtmdet_exp6_rig_20260725.tar.gz`
  (196MB)로 압축(기존 `~/rtmdet_checkpoints_20260723.tar.gz`·work_dirs 무변경, 새 파일만). 스테이션
  반출용 ONNX 경로: `work_dirs/exp6_rig/onnx_export/end2end.onnx`.

## 실험7: 파렛트 증강 재학습 (2026-07-25, Jira 156) — exp6 정면 대결

**대전제**: 실험6은 이미 성공·반출 완료. 실험7은 파렛트 증강을 더 넣어 "exp6을 이기나" 확인하는
별도 실험 — **이겨야만 교체, 못 이기면 버리고 exp6 유지**. exp6 work_dir/체크포인트/onnx 무변경,
exp7은 새 경로에만 씀.

**추가 데이터**(git pull 막혀 datasets.yaml 3소스를 수동 wiring — astraws_pallet/astraws_dark/
rig_composites, split별 블록 7개 추가, `configs/datasets.yaml`):
- **astraws_pallet**(원본 `Pallet Detection.v1i.coco.zip`, extract_roboflow로 `data/raw/astraws_pallet/
  {train,valid,test}`): plastic pallet+wood pallet → pallet. 879장 중 파렛트 라벨 있는 792장 채택
  (label 없는 87장은 convert 기본 empty-drop). **파렛트 1340개 전량 보존**.
- **astraws_dark**(저조도 쌍둥이, `data/processed/astraws_dark/{train,valid,test}`): 동일 매핑,
  파일명 `dark_` 접두사라 원본과 identity 안 겹쳐 dedup 안 됨(의도). 792장/**파렛트 1340**.
- **rig_composites**(합성, `data/processed/rig_composites`, split 없음): 이미 box=1/pallet=2 스키마.
  300장/box 303·pallet 300. 네거티브 아님(empty-GT 0).
- 병합은 레포 `convert_coco`를 datasets.yaml wiring 그대로 읽어 수행(`scripts/make_exp7_merge.py`),
  staged_images/<prefix> 심볼릭 링크 7개 생성.

**rig oversampling 5배 — RepeatDataset 대신 json 복제**: 원래 지시는 RepeatDataset(times=5)였으나,
RTMDet `PipelineSwitchHook`가 **top-level dataset의 .pipeline만 교체**해서 ConcatDataset/RepeatDataset로
감싸면 ep40 stage2 약증강 전환이 서브셋에 안 먹는다(hook 소스 확인). exp6의 큰 상승이 이 전환에서
나왔으므로 감싸면 공정 비교가 깨진다. → rig 289장을 json 레벨에서 5배 복제해 **단일 CocoDataset 유지**
(oversampling 효과 동일 + exp6과 학습 메커니즘 일치). rig 네거티브 8장도 ×5=40장 복제되어 보존.

**최종 학습셋**(`box_coco_train_exp7.json`, `scripts/make_exp7_merge.py`):

| source | images | box | pallet |
|---|---|---|---|
| Cardboard | 7,082 | 14,181 | 0 |
| SCD | 6,735 | 70,838 | 0 |
| LOCO | 3,419 | 19,582 | 83,758 |
| astraws_pallet | 792 | 0 | 1,340 |
| astraws_dark | 792 | 0 | 1,340 |
| rig_composites | 300 | 303 | 300 |
| **base 소계** | **19,120** | **104,904** | **86,738** |
| rig ×5 (289×5) | 1,445 | 4,355 | 1,365 |
| **TOTAL(1ep 노출)** | **20,565** | **109,259** | **88,103** |

exp6 대비 파렛트 증강 유효 기여: astraws 1340 + dark 1340 + composites 300 + rig×5 1365(exp6은
rig×1 273). val은 `box_coco_val.json` 고정(astraws/rig 계열 val 절대 금지).

**config**(`configs/rtmdet_m_800_astraws_forklift.py`, work_dir `exp7_aug`): exp6 config 복사 후
ann_file만 `box_coco_train_exp7.json`으로 교체. 나머지 전부 exp6과 동일 — 실험5 best warm-start,
lr 1e-4 고정, batch16@800, 50ep, val 매 epoch, stage2 ep40, save_best mAP_50, filter_empty_gt=False.
스모크(200장 1ep) 통과: 20565장 로드·negatives 42장(rig8×5+SCD2) 보존 확인·메모리 20.8GB.

- 2026-07-25 16:33 — **실험7 풀 학습 기동**: PID 1210430, GPU1, `work_dirs/exp7_aug`, 로그
  `work_dirs/exp7_aug/train_20260725_163344.log`. 메모리 24.35GB + Isaac Sim 7.2GB = 31.6GB/46GB(안전,
  PID 1140907 무영향). 1286 iter/epoch × 50ep, ETA ~9h. exp6 산출물 무변경 확인. 완료 감지 모니터 설정.
- 2026-07-26 01:56 — **실험7 완료**: 50ep 완주(GPU1 유휴 복귀, Isaac 무영향). val 곡선은 exp6과 거의
  겹침 — stage1 0.70~0.727 밴드 → ep40 stage2 전환 후 상승(ep41 0.751 → ep49 0.763 best → ep50 0.762).
  **best `work_dirs/exp7_aug/best_coco_bbox_mAP_50_epoch_49.pth`**:

  | 지표 | exp7 ep49 | exp6 ep49 | 증감 |
  |---|---|---|---|
  | mAP_50 | 0.763 | 0.765 | -0.002 |
  | box mAP_50 | **0.835** | 0.832 | +0.003 |
  | pallet mAP_50 | 0.691 | 0.692 | -0.001 |

  LOCO val 기준으론 exp6과 사실상 동률(노이즈 범위). **승부는 rig 실추론에서 갈림.**
- 2026-07-26 01:57 — **실험7 판정: rig 12프레임 정면 대결(핵심)**. `scripts/infer_rig_compare.py`에
  exp7 추가, exp5/exp6/exp7 동일 12프레임 대조:

  | 모델 | 파렛트 score>=0.5 | 평균 max score | 범위 |
  |---|---|---|---|
  | exp5(old) | 0/12 | 0.068 | 0.037~0.112 |
  | exp6(rig) | 11/12 | 0.848 | 0.492~0.917 |
  | **exp7(aug)** | **12/12** | **0.892** | **0.736~0.949** |

  exp7이 **12프레임 전부 exp6보다 score 상승**, exp6이 놓쳤던 truncated 프레임 0013도 0.492→0.736으로
  임계 통과(12/12 달성). **승격 게이트 두 조건 모두 충족**: (1) rig 검출 12/12·평균 0.892 ≥ exp6(11/12·
  0.848), (2) box mAP_50 0.835 ≥ exp6 0.832(회귀 없음, +0.003). → **exp7으로 교체(승격)**.
- 2026-07-26 01:57 — **실험7 ONNX export**: 동일 mmdeploy 레시피, model_cfg/checkpoint만 exp7으로 교체.
  산출물 **`work_dirs/exp7_aug/onnx_export/end2end.onnx`**(109MB, @800, opset11). 검증: onnx.checker
  통과, 인터페이스 `input[batch,3,h,w]`→`dets`+`labels`(exp5/6과 동일 = 스테이션 코드 무수정 호환),
  정규화 호출자 책임(그래프 내 정규화 노드 없음). rig 2장에서 torch vs onnxruntime top-3 score 4자리
  완전 일치. 백업 `~/rtmdet_exp7_aug_20260726.tar.gz`(196MB, 기존 tar·work_dirs 무변경).
  **최종 스테이션 반출 모델 = 실험7**. exp6 산출물은 롤백 대비 그대로 보존(work_dirs/exp6_rig 무변경).
