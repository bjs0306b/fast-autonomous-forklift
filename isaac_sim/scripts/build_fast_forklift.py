#!/usr/bin/env python3
"""NVIDIA ForkliftB 에셋에 F.A.S.T. 실물 사양 override를 얹어 fast_forklift.usd 를 생성한다.

원본을 복사·수정하지 않고 reference + override 로 처리하므로 결과 USD 는 작고
git diff 로 검토 가능하다. 사양이 바뀌면 fast_params.py 만 고치고 재실행한다.

실행:
    python3 scripts/build_fast_forklift.py

pxr(USD) 모듈은 Isaac Sim 설치본에서 자동으로 찾는다. Isaac Sim GUI/GPU 불필요.
"""
import glob
import os
import sys

# --- pxr 부트스트랩 -------------------------------------------------------
# isaacsim/python.sh 는 기본적으로 pxr 을 sys.path 에 올려주지 않으므로 직접 찾는다.
def _bootstrap_pxr():
    try:
        import pxr  # noqa: F401
        return
    except ImportError:
        pass

    isaac_root = os.environ.get("ISAAC_SIM_ROOT", os.path.expanduser("~/isaacsim"))
    matches = glob.glob(os.path.join(isaac_root, "extscache", "omni.usd.libs-*"))
    if not matches:
        sys.exit(f"omni.usd.libs 를 찾지 못했습니다. ISAAC_SIM_ROOT 확인: {isaac_root}")
    usd_lib = matches[0]

    # USD 바이너리는 Isaac 번들 python(3.11) 용으로 빌드돼 있어 시스템 python(3.10)
    # 으로는 libpython3.11.so 를 못 찾는다. Isaac 쪽 인터프리터로 갈아탄다.
    isaac_py = [
        p for p in glob.glob(os.path.join(isaac_root, "kit", "python", "bin", "python3*"))
        if not p.endswith("-config")
    ]
    interp = sorted(isaac_py)[-1] if isaac_py else sys.executable

    # libusd_*.so 가 있는 bin 을 로더에 알려야 하는데, LD_LIBRARY_PATH 는 프로세스
    # 시작 시점에만 읽히므로 여기서 설정하고 자기 자신을 한 번 재실행한다.
    lib_bin = os.path.join(usd_lib, "bin")
    py_lib = os.path.join(isaac_root, "kit", "python", "lib")
    if os.environ.get("_FAST_USD_BOOTSTRAPPED") != "1":
        os.environ["_FAST_USD_BOOTSTRAPPED"] = "1"
        os.environ["LD_LIBRARY_PATH"] = ":".join(
            [lib_bin, py_lib, os.environ.get("LD_LIBRARY_PATH", "")]
        )
        os.environ["PYTHONPATH"] = usd_lib + ":" + os.environ.get("PYTHONPATH", "")
        os.execv(interp, [interp] + sys.argv)

    sys.path.insert(0, usd_lib)


_bootstrap_pxr()

from pxr import Gf, Sdf, Usd, UsdGeom  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src", "forklift_control"))
from forklift_control import fast_params as P  # noqa: E402

# NVIDIA 기본 에셋. 센서(전방 2D 라이다) 포함 변형.
SOURCE_USD = (
    "https://omniverse-content-production.s3-us-west-2.amazonaws.com"
    "/Assets/Isaac/5.1/Isaac/Robots/IsaacSim/ForkliftB/forklift_b_sensor.usd"
)
OUT_USD = os.path.join(os.path.dirname(__file__), "..", "assets", "fast_forklift.usd")


# ForkliftB 마스트 기구가 허용하는 최대 행정. 이걸 넘기면 포크가 마스트를 벗어난다.
MAST_TRAVEL_MAX_M = 2.0


def main():
    if P.FORK_STROKE_SIM_M > MAST_TRAVEL_MAX_M:
        sys.exit(
            f"포크 행정이 마스트 한계를 초과합니다: "
            f"{P.FORK_STROKE_REAL_M} m x {P.SCALE} = {P.FORK_STROKE_SIM_M} m > {MAST_TRAVEL_MAX_M} m\n"
            f"  -> fast_params.py 에서 SCALE 을 {MAST_TRAVEL_MAX_M / P.FORK_STROKE_REAL_M:.1f} 이하로 낮추거나\n"
            f"     FORK_STROKE_REAL_M 을 재확인하세요."
        )

    os.makedirs(os.path.dirname(os.path.abspath(OUT_USD)), exist_ok=True)

    stage = Usd.Stage.CreateNew(os.path.abspath(OUT_USD)) if not os.path.exists(OUT_USD) \
        else Usd.Stage.Open(os.path.abspath(OUT_USD))
    stage.GetRootLayer().Clear()

    UsdGeom.SetStageUpAxis(stage, UsdGeom.Tokens.z)
    UsdGeom.SetStageMetersPerUnit(stage, 1.0)

    world = UsdGeom.Xform.Define(stage, "/World")
    stage.SetDefaultPrim(world.GetPrim())

    # 원본 지게차를 레퍼런스로 끌어온다.
    fork = stage.DefinePrim(P.PRIM_ROOT, "Xform")
    fork.GetReferences().AddReference(SOURCE_USD)

    # --- 조향 한계를 실물 서보 사양으로 조인다 --------------------------
    # 기본 +-60도를 그대로 두면 실물이 못 도는 경로를 Nav2 가 계획한다.
    steer = stage.OverridePrim(P.PRIM_STEER_JOINT)
    steer.CreateAttribute("physics:lowerLimit", Sdf.ValueTypeNames.Float).Set(-P.STEER_LIMIT_DEG)
    steer.CreateAttribute("physics:upperLimit", Sdf.ValueTypeNames.Float).Set(P.STEER_LIMIT_DEG)

    # --- 포크 행정거리를 실물 스트로크(축척 반영)로 조인다 --------------
    lift = stage.OverridePrim(P.PRIM_LIFT_JOINT)
    lift.CreateAttribute("physics:lowerLimit", Sdf.ValueTypeNames.Float).Set(0.0)
    lift.CreateAttribute("physics:upperLimit", Sdf.ValueTypeNames.Float).Set(P.FORK_STROKE_SIM_M)

    # --- 구동륜을 속도 제어로 전환 --------------------------------------
    # 원본은 stiffness=100 이라 관절이 targetPosition(=0) 으로 되돌아가려 한다.
    # 계속 회전해야 하는 바퀴에서는 이게 제동처럼 작용해 안 돌거나 떨린다.
    # stiffness=0, damping>0 이어야 순수 속도 제어가 된다.
    drive = stage.OverridePrim(P.PRIM_DRIVE_JOINT)
    drive.CreateAttribute("drive:angular:physics:stiffness", Sdf.ValueTypeNames.Float).Set(0.0)

    # --- 불필요한 스테레오 카메라 비활성화 (VRAM) -----------------------
    for path in P.PRIMS_TO_DISABLE:
        stage.OverridePrim(path).SetActive(False)

    stage.GetRootLayer().Save()

    print(f"생성: {os.path.abspath(OUT_USD)}")
    print(f"  축척          실물 = 시뮬 / {P.SCALE}")
    print(f"  조향 한계     +-{P.STEER_LIMIT_DEG} deg   (원본 +-60)")
    print(f"  포크 행정     0 ~ {P.FORK_STROKE_SIM_M} m (시뮬) = {P.FORK_STROKE_REAL_M} m (실물)")
    print(f"  비활성화      {len(P.PRIMS_TO_DISABLE)} 개 카메라")


if __name__ == "__main__":
    main()
