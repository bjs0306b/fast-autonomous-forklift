"""mmdeploy TensorRT deploy config — 온보드 -s Jetson Orin Nano 배포용 (S15P11A304-68).

기존 스테이션 ONNX(`ai/models/end2end.onnx`)는 **onnxruntime deploy config**로 뽑혀
TensorRT에서 그대로 빌드가 안 된다. 2026-07-27 Orin Nano 실기(TensorRT 10.3)에서
`trtexec`로 확인한 블로커 2개(Jira 68):

  1. **동적 입력 shape** (`batch,3,height,width`) → TRT는 concrete shape가 필요.
     빌드 시 `/neck/Concat: axis 2 dimensions must be equal` 로 실패.
  2. **pre_top_k > 3840** → `ITopKLayer /TopK: K exceeds the maximum value allowed (3840)`.
     TensorRT TopK 하드 한계 초과. mmdeploy 기본 tensorrt config가 pre_top_k=5000이라 걸린다.

이 config는 둘 다 잡는다: **정적 입력 shape** + **pre_top_k=3000(≤3840)**.

--------------------------------------------------------------------------------
사용법 (GPU 서버, mmdeploy 환경 `rtmdet`):

    python tools/deploy.py \
        <이 파일 경로>/detection_tensorrt_static_onboard.py \
        <모델 config .py> \        # 예: rtmdet_s_*_forklift.py
        <체크포인트 .pth> \        # -s ep99 (번들 rtmdet_checkpoints_20260723.tar.gz 내)
        <테스트 이미지 .jpg> \
        --work-dir work_dirs/onboard_trt \
        --device cuda --dump-info

⚠️ deploy.py는 ONNX를 뽑은 뒤 **실행 머신(서버 GPU)** 에서 엔진까지 빌드한다. 그 서버
   엔진은 Orin Nano와 호환 안 되니 **버린다** — 우리가 챙길 산출물은 `end2end.onnx`뿐.
   (엔진은 기기 종속이라 반드시 타깃에서 빌드해야 한다.)

산출 `end2end.onnx`를 Orin Nano로 복사한 뒤 거기서 엔진 빌드:

    /usr/src/tensorrt/bin/trtexec --onnx=end2end.onnx --fp16 \
        --saveEngine=onboard_s.engine

--------------------------------------------------------------------------------
⚠️ INPUT_SIZE는 -s 학습·추론 입력과 반드시 일치시킬 것. 온보드 속도 위해 640 기본.
   (-m 재-export 테스트로 쓰려면 (800, 800)으로 바꾼다.)
"""

# (width, height). 온보드 -s 기본. 145에서 -s를 이 크기로 학습·추론하도록 맞춘다.
INPUT_SIZE = (640, 640)

codebase_config = dict(
    type='mmdet',
    task='ObjectDetection',
    model_type='end2end',
    post_processing=dict(
        score_threshold=0.05,          # 후보를 넉넉히 남기고 임계는 추론단(config)에서
        confidence_threshold=0.005,
        iou_threshold=0.65,            # RTMDet test_cfg nms 기본
        max_output_boxes_per_class=200,
        pre_top_k=3000,                # ★ 68 블로커2 해결 — TensorRT TopK 한계(3840) 이하
        keep_top_k=300,
        background_label_id=-1,
    ),
)

onnx_config = dict(
    type='onnx',
    export_params=True,
    keep_initializers_as_inputs=False,
    opset_version=11,                  # TRT 10.3 호환(널널). RTMDet end2end 표준
    save_file='end2end.onnx',
    input_names=['input'],
    output_names=['dets', 'labels'],
    input_shape=INPUT_SIZE,            # ★ 68 블로커1 해결 — 정적 shape
    optimize=True,
)

backend_config = dict(
    type='tensorrt',
    common_config=dict(fp16_mode=True, max_workspace_size=1 << 30),
    model_inputs=[
        dict(input_shapes=dict(input=dict(
            # [N, C, H, W] — 정적이므로 min=opt=max
            min_shape=[1, 3, INPUT_SIZE[1], INPUT_SIZE[0]],
            opt_shape=[1, 3, INPUT_SIZE[1], INPUT_SIZE[0]],
            max_shape=[1, 3, INPUT_SIZE[1], INPUT_SIZE[0]],
        )))
    ],
)
