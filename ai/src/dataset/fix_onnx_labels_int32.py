"""mmdeploy end2end onnx의 labels 출력을 INT64→INT32로 되돌린다 (S15P11A304-68).

⚠️ **68 엔진 빌드의 필수 후처리다.** 2026-07-30 실측:

  epoch 116을 mmdeploy로 export한 onnx가 `labels` 출력을 **INT64**로 냈고,
  젯슨 TensorRT 10.3이 파싱에서 거부했다:
    [8] Assertion failed: For INT32 tensors, the output type must also be INT32.

  같은 deploy config로 07-29에 만든 onnx는 labels가 **INT32**라 빌드됐다(9.6분).
  두 onnx는 노드 401개·타입·opset 11이 완전히 동일했고 **차이는 labels dtype뿐**.
  mmdeploy/torch export가 그 사이 labels를 INT64로 내게 바뀐 것으로 보인다.

labels를 내는 마지막 노드(Reshape) 뒤에 Cast(INT32)를 끼우고 출력 선언도 INT32로
바꾼다. 값 자체는 클래스 인덱스(0·1)라 INT32로 안전하다.

    # 서버(rtmdet env)에서 export 직후
    python -m dataset.fix_onnx_labels_int32 \
        work_dirs/onboard_export/end2end.onnx \
        work_dirs/onboard_export/end2end_int32.onnx

이후 end2end_int32.onnx를 젯슨으로 보내 trtexec로 빌드한다(runbook §4-③).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import onnx
from onnx import TensorProto, helper


def fix(src: Path, dst: Path) -> int:
    m = onnx.load(str(src))
    g = m.graph

    labels_out = next((o for o in g.output if o.name == "labels"), None)
    if labels_out is None:
        print("⚠️ 'labels' 출력이 없다 — mmdeploy end2end onnx가 맞는지 확인", file=sys.stderr)
        return 1
    cur = TensorProto.DataType.Name(labels_out.type.tensor_type.elem_type)
    print(f"labels dtype 현재: {cur}")
    if cur == "INT32":
        print("이미 INT32 — 변환 불필요")
        onnx.save(m, str(dst))
        return 0

    inner = "labels_pre_int32"
    producer = None
    for n in g.node:
        for i, out in enumerate(n.output):
            if out == "labels":
                n.output[i] = inner
                producer = n
    if producer is None:
        print("⚠️ labels를 내는 노드를 못 찾음", file=sys.stderr)
        return 1
    print(f"labels producer: {producer.op_type}")

    g.node.append(helper.make_node("Cast", [inner], ["labels"],
                                   to=TensorProto.INT32, name="labels_to_int32"))
    labels_out.type.tensor_type.elem_type = TensorProto.INT32

    onnx.checker.check_model(m)
    dst.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(m, str(dst))
    print(f"저장 → {dst}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="onnx labels INT64→INT32")
    ap.add_argument("src", type=Path, help="mmdeploy end2end.onnx")
    ap.add_argument("dst", type=Path, help="출력 onnx")
    a = ap.parse_args(argv)
    return fix(a.src, a.dst)


if __name__ == "__main__":
    raise SystemExit(main())
