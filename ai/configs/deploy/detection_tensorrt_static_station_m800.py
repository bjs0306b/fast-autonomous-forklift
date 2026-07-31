"""mmdeploy TensorRT deploy config — **스테이션 -m @800**을 젯슨에서 돌리기 위한 재-export.

`detection_tensorrt_static_onboard.py`(온보드 -s @640)와 같은 구조이고 **입력 크기만
800**이다. 그 파일 주석이 예고한 "(-m 재-export 테스트로 쓰려면 (800, 800)으로 바꾼다)"
가 이 파일이다.

--------------------------------------------------------------------------------
## 왜 필요한가 (2026-07-31)

측정 모델 추론을 스테이션 PC가 아니라 **온보드 보드에서 돌리자**는 검토가 나왔다
(평가자 관점에서 "AI가 노트북에서 돈다"로 보이는 문제). 현역 `ai/models/end2end.onnx`를
그대로 젯슨 TensorRT에 넣어보니 실패했다:

    Error Code 4: API Usage Error
    (ITopKLayer /TopK: K exceeds the maximum value allowed (3840))

현역 ONNX는 **onnxruntime deploy config로 뽑혀** `pre_top_k`가 TensorRT 한계를 넘는다.
@800이면 앵커가 100²+50²+25² = 13,125개라 기본값(5000)도 초과다. 68에서 온보드 -s가
겪은 것과 **똑같은 블로커**이고, 해법도 같다 — `pre_top_k=3000` + 정적 shape.

## ⚠️ 이 export는 현역 ONNX와 같은 그래프가 아니다

`pre_top_k`가 달라지므로 **NMS에 들어가는 후보 집합이 달라질 수 있다.** 상위 3000개면
실질적으로 충분하지만 "같은 모델이니 같은 결과"라고 단정할 수 없다.

→ 이걸로 교체하려면 **치수 KPI(평균 0.66mm / 최대 2.10mm, 기준 ≤4mm)를 리그에서
재측정**해야 한다. 검증 없이 현역 `end2end.onnx`를 대체하지 말 것.

## 사용법 (GPU 서버, conda env `rtmdet`)

    cd ~/S15P11A304/ai
    CUDA_VISIBLE_DEVICES=1 python mmdeploy_tools/deploy.py \
        configs/deploy/detection_tensorrt_static_station_m800.py \
        configs/rtmdet_m_800_occ_forklift.py \
        work_dirs/exp8_occ/best_coco_bbox_mAP_50_epoch_48.pth \
        <샘플 이미지> --work-dir work_dirs/station_m800_trt --device cuda:0

서버 mmdeploy는 pip 설치라 `tools/deploy.py`가 없다 → 젯슨 `~/mmdeploy/tools/`를
노트북 경유로 올려 쓴다(runbook §① 동일).

deploy.py는 ONNX를 뽑은 뒤 **서버 GPU에서** 엔진까지 빌드하려 하는데, 그 엔진은
Orin Nano와 호환되지 않으므로 **버린다.** 챙길 산출물은 `end2end.onnx` 하나다.

⚠️ **labels dtype 후처리 필수** — mmdeploy가 `labels`를 INT64로 내면 젯슨 TRT 10.3이
파싱을 거부한다(68 최대 함정). `dataset.fix_onnx_labels_int32`로 되돌린 뒤 빌드할 것.
"""

# (width, height). 스테이션 -m 학습·추론 입력과 일치시킨다 — 바꾸면 치수가 다 틀어진다.
INPUT_SIZE = (800, 800)

codebase_config = dict(
    type='mmdet',
    task='ObjectDetection',
    model_type='end2end',
    post_processing=dict(
        score_threshold=0.05,          # 후보를 넉넉히 남기고 임계는 추론단(config)에서
        confidence_threshold=0.005,
        iou_threshold=0.65,            # RTMDet test_cfg nms 기본
        max_output_boxes_per_class=200,
        pre_top_k=3000,                # ★ TensorRT TopK 한계(3840) 이하
        keep_top_k=300,
        background_label_id=-1,
    ),
)

onnx_config = dict(
    type='onnx',
    export_params=True,
    keep_initializers_as_inputs=False,
    opset_version=11,                  # TRT 10.3 호환. RTMDet end2end 표준
    save_file='end2end.onnx',
    input_names=['input'],
    output_names=['dets', 'labels'],
    input_shape=INPUT_SIZE,            # ★ 정적 shape (동적이면 neck Concat에서 실패)
    optimize=True,
)

backend_config = dict(
    type='tensorrt',
    # fp16_mode는 서버 빌드 단계에만 영향을 준다(그 엔진은 버린다). 젯슨에서 다시 빌드할 때
    # **FP32로 빌드**하면 양자화가 없어 현역 정확도에 가장 가깝다.
    common_config=dict(fp16_mode=False, max_workspace_size=1 << 30),
    model_inputs=[
        dict(input_shapes=dict(input=dict(
            min_shape=[1, 3, INPUT_SIZE[1], INPUT_SIZE[0]],
            opt_shape=[1, 3, INPUT_SIZE[1], INPUT_SIZE[0]],
            max_shape=[1, 3, INPUT_SIZE[1], INPUT_SIZE[0]],
        )))
    ],
)
