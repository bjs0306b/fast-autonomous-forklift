#!/usr/bin/env python3
"""시뮬 창고의 점유격자(.pgm + .yaml)를 생성한다.

시뮬에서는 SLAM 을 돌리지 않는다. 씬을 직접 만들었으므로 벽 위치를 이미 알고,
그리는 편이 더 빠르고 정확하다. SLAM 은 실물 Orin 이 물리 목업을 맵핑할 때만
쓴다(D 담당).

씬 축척: ff.usd 창고 = 20 x 30 m, 실물 크기(미니어처의 10배).
좌표는 이 10배 세계 기준 미터다. Nav2 도 이 축척에서 돌고, 실물 연동은
MQTT 경계에서만 1/10 변환한다.

실행 (일반 터미널, Isaac API 불필요):
    python3 nav2/make_map.py
"""
import os

# ---------------------------------------------------------------------------
# 씬 정의 — ff.usd 와 일치시킬 것.
# ---------------------------------------------------------------------------
RESOLUTION = 0.05          # m/px. 20x30m 창고엔 5cm 가 적당(400x600px).
ORIGIN = (0.0, 0.0)        # 맵 왼쪽아래 구석의 세계 좌표 = 창고 구석

WORLD_W = 20.0             # 창고 폭   (m, +X)
WORLD_H = 30.0             # 창고 깊이 (m, +Y)

WALL_THICKNESS = 0.2       # 외벽 두께(m). 10배 세계라 얇으면 라이다가 관통한다.

# 내부 장애물(랙·기둥 등)은 maps/obstacles.txt 에서 읽는다.
#
# 이 목록은 "이름이 Rack 인 것"이 아니라 "라이다 스캔면 높이(1.2m)에서 실제로
# 빔을 막는 것"을 담는다. 즉 실물 라이다가 보게 될 것과 정확히 일치한다.
# 선반 위 팔레트(높이 2m)나 바닥 팔레트(0.15m)처럼 스캔면을 벗어난 것은 자동으로
# 빠진다. 이름 기반보다 정확하고, 놓치는 랙이 없다.
#
# 갱신법 — Isaac Script Editor 에서 (씬 열린 상태):
#   from pxr import UsdGeom, Usd; import omni.usd
#   stage = omni.usd.get_context().get_stage()
#   cache = UsdGeom.BBoxCache(Usd.TimeCode.Default(), ['default'])
#   LIDAR_Z = 1.2
#   with open('/home/ubuntu/forklift_ws/nav2/maps/obstacles.txt','w') as f:
#       for p in stage.Traverse():
#           if not p.IsA(UsdGeom.Xformable) or 'Forklift' in str(p.GetPath()): continue
#           b = cache.ComputeWorldBound(p).ComputeAlignedRange(); mn,mx=b.GetMin(),b.GetMax()
#           if abs(mx[0])>1e5 or mx[0]<=mn[0]: continue
#           if (mx[0]-mn[0])<0.1 or (mx[1]-mn[1])<0.1: continue
#           if mn[2]<=LIDAR_Z<=mx[2] and not(mn[0]<0 and mn[1]<0 and mx[0]>20 and mx[1]>30):
#               f.write(f"{mn[0]:.2f} {mn[1]:.2f} {mx[0]:.2f} {mx[1]:.2f}\n")
OBSTACLE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "maps", "obstacles.txt")


def load_obstacles():
    """maps/obstacles.txt 를 (x_min, y_min, x_max, y_max) 목록으로 읽는다."""
    if not os.path.exists(OBSTACLE_FILE):
        return []
    out = []
    with open(OBSTACLE_FILE) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.replace(",", " ").split()
            if len(parts) >= 4:
                out.append(tuple(float(v) for v in parts[:4]))
    return out


RACKS = load_obstacles()
WALLS = []                 # 추가 벽/기둥, 같은 형식.

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "maps")
OUT_NAME = "sim_warehouse"

# ---------------------------------------------------------------------------
FREE = 254        # 흰색 - 주행 가능
OCCUPIED = 0      # 검정 - 벽


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def world_to_px(x, y, width_px, height_px):
    """세계 좌표(m) -> 픽셀 인덱스.

    이미지 행은 위->아래, 세계 Y 는 아래->위라 행을 뒤집는다. 이걸 틀리면
    맵이 상하로 뒤집혀서 지게차가 벽으로 돌진한다(좌우는 맞아 놓치기 쉽다).
    """
    col = int(round((x - ORIGIN[0]) / RESOLUTION))
    row = height_px - 1 - int(round((y - ORIGIN[1]) / RESOLUTION))
    return clamp(col, 0, width_px - 1), clamp(row, 0, height_px - 1)


def fill_rect(grid, width_px, height_px, rect, value=OCCUPIED):
    x0, y0, x1, y1 = rect
    c0, r1 = world_to_px(x0, y0, width_px, height_px)
    c1, r0 = world_to_px(x1, y1, width_px, height_px)
    for r in range(min(r0, r1), max(r0, r1) + 1):
        for c in range(min(c0, c1), max(c0, c1) + 1):
            grid[r * width_px + c] = value


def build():
    width_px = int(round(WORLD_W / RESOLUTION))
    height_px = int(round(WORLD_H / RESOLUTION))
    grid = bytearray([FREE] * (width_px * height_px))

    t = WALL_THICKNESS
    perimeter = [
        (0.0, 0.0, WORLD_W, t),                  # 아래
        (0.0, WORLD_H - t, WORLD_W, WORLD_H),    # 위
        (0.0, 0.0, t, WORLD_H),                  # 왼쪽
        (WORLD_W - t, 0.0, WORLD_W, WORLD_H),    # 오른쪽
    ]
    for rect in perimeter + WALLS + RACKS:
        fill_rect(grid, width_px, height_px, rect)
    return grid, width_px, height_px


def write_pgm(path, grid, width_px, height_px):
    with open(path, "wb") as f:
        f.write(b"P5\n")
        f.write(f"{width_px} {height_px}\n255\n".encode())
        f.write(bytes(grid))


# 실물 = 시뮬 / SCALE. 실물 목업을 시뮬 배치대로 만들었으므로 같은 맵을
# 축척만 바꿔 재사용한다. D 의 SLAM 을 대체한다(시작 지점 고정 전제).
SCALE = 10.0


def write_yaml(path, pgm_name, resolution, origin):
    with open(path, "w") as f:
        f.write(
            f"image: {pgm_name}\n"
            f"resolution: {resolution}\n"
            f"origin: [{origin[0]}, {origin[1]}, 0.0]\n"
            "negate: 0\n"
            "occupied_thresh: 0.65\n"
            "free_thresh: 0.196\n"
        )


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    grid, w, h = build()

    pgm_path = os.path.join(OUT_DIR, f"{OUT_NAME}.pgm")
    write_pgm(pgm_path, grid, w, h)

    # 시뮬용 (10배 세계)
    sim_yaml = os.path.join(OUT_DIR, f"{OUT_NAME}.yaml")
    write_yaml(sim_yaml, f"{OUT_NAME}.pgm", RESOLUTION, ORIGIN)

    # 실물용 (미니어처). 같은 pgm 을 참조하고 resolution/origin 만 1/SCALE.
    # 픽셀 데이터는 동일 — 각 픽셀이 5cm 대신 5mm 로 해석되어 맵이 1/10 이 된다.
    real_yaml = os.path.join(OUT_DIR, f"{OUT_NAME}_real.yaml")
    real_origin = (ORIGIN[0] / SCALE, ORIGIN[1] / SCALE)
    write_yaml(real_yaml, f"{OUT_NAME}.pgm", RESOLUTION / SCALE, real_origin)

    print(f"{w} x {h} px, 랙 {len(RACKS)}개, 추가벽 {len(WALLS)}개")
    print(f"  시뮬: {WORLD_W} x {WORLD_H} m @ {RESOLUTION} m/px      -> {sim_yaml}")
    print(f"  실물: {WORLD_W/SCALE} x {WORLD_H/SCALE} m @ {RESOLUTION/SCALE} m/px -> {real_yaml}")
    print(f"  pgm (공용): {pgm_path}")


if __name__ == "__main__":
    main()
