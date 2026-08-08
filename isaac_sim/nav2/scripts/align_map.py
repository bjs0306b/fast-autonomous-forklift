"""SLAM 맵을 시뮬 맵에 정합해서 origin 을 구한다.

    실물 SLAM 맵  ──[회전 θ, 이동 dx dy]──▶  시뮬 좌표계

오린카가 SLAM 으로 만든 맵은 원점이 로봇 시작 위치라 우리 규격과 다르다.
두 맵의 **벽 모양을 겹쳐** 맞는 변환을 찾고, 그 값을 yaml 의 origin 에 넣으면
오린카가 내보내는 좌표가 그대로 시뮬 좌표계가 된다. 코드는 안 고쳐도 된다.

    python3 align_map.py real_set.yaml
    python3 align_map.py real_set.yaml --preview /tmp/align.png

동작
    1. 두 맵을 같은 픽셀 크기로 맞춘다 (시뮬은 실물의 10배)
    2. 회전·이동을 훑으며 벽이 가장 잘 겹치는 값을 찾는다 (거칠게 → 곱게)
    3. 그 변환을 origin 으로 환산해 출력한다

주의
    시뮬 맵에는 랙이 꽉 찬 블록으로 그려져 있지만, 실물 라이다(200mm)는
    낮은 랙(140mm)을 못 본다. 그래서 정합에는 **바깥 벽만** 쓴다
    (--use-racks 로 랙까지 포함시킬 수 있다).
"""
import argparse
import math
import os
import sys

import numpy as np
from PIL import Image

SIM_MAP = "/home/ubuntu/forklift_ws/nav2/maps/sim_warehouse.pgm"
SIM_RES = 0.05          # 시뮬 맵 해상도 (m/px)
SCALE = 10.0            # 시뮬 = 실물 x SCALE


def load_pgm(path):
    a = np.array(Image.open(path))
    return a


def occupied(img, thr=100):
    """점유 픽셀 (검정)."""
    return img < thr


def read_yaml(path):
    try:
        import yaml
        with open(path) as f:
            return yaml.safe_load(f)
    except Exception as e:
        raise SystemExit(f"yaml 읽기 실패: {e}")


def wall_only(occ, margin_px):
    """바깥 테두리 근처만 남긴다 (랙 등 내부 구조 제외).

    라이다가 낮은 랙을 못 보므로, 정합은 바깥 벽으로만 한다.
    """
    h, w = occ.shape
    keep = np.zeros_like(occ)
    keep[:margin_px, :] = True
    keep[-margin_px:, :] = True
    keep[:, :margin_px] = True
    keep[:, -margin_px:] = True
    return occ & keep


_PIVOT = np.zeros(2)


def rot_translate(pts, th, dx, dy):
    """무게중심(_PIVOT)을 축으로 회전한 뒤 이동한다.

    원점을 축으로 돌리면 각도가 조금만 바뀌어도 위치가 크게 튀어서,
    회전과 이동을 따로 훑을 수가 없다.
    """
    c, s = math.cos(th), math.sin(th)
    x = pts[:, 0] - _PIVOT[0]
    y = pts[:, 1] - _PIVOT[1]
    return np.stack([c * x - s * y + _PIVOT[0] + dx,
                     s * x + c * y + _PIVOT[1] + dy], axis=1)


def score(src_pts, dst_mask, th, dx, dy):
    """변환한 점들이 목표 마스크 위에 얼마나 얹히나."""
    p = rot_translate(src_pts, th, dx, dy)
    r = np.round(p[:, 1]).astype(int)
    c = np.round(p[:, 0]).astype(int)
    H, W = dst_mask.shape
    ok = (r >= 0) & (r < H) & (c >= 0) & (c < W)
    if not ok.any():
        return 0.0
    return float(dst_mask[r[ok], c[ok]].sum()) / len(src_pts)


def search(src_pts, dst_mask, th_range, xy_range, th_step, xy_step, best=None):
    """거친 격자 탐색. best=(score, th, dx, dy) 를 갱신해 돌려준다."""
    if best is None:
        best = (-1.0, 0.0, 0.0, 0.0)
    th0 = best[1]
    for th in np.arange(th0 - th_range, th0 + th_range + 1e-9, th_step):
        for dx in np.arange(best[2] - xy_range, best[2] + xy_range + 1e-9,
                            xy_step):
            for dy in np.arange(best[3] - xy_range, best[3] + xy_range + 1e-9,
                                xy_step):
                s = score(src_pts, dst_mask, th, dx, dy)
                if s > best[0]:
                    best = (s, float(th), float(dx), float(dy))
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("real_yaml", help="SLAM 으로 만든 맵의 yaml")
    ap.add_argument("--sim", default=SIM_MAP, help="시뮬 맵 pgm")
    ap.add_argument("--use-racks", action="store_true",
                    help="정합에 내부 구조(랙)까지 쓴다")
    ap.add_argument("--preview", help="정합 결과를 그림으로 저장")
    a = ap.parse_args()

    meta = read_yaml(a.real_yaml)
    real_img_path = meta["image"]
    if not os.path.isabs(real_img_path):
        real_img_path = os.path.join(os.path.dirname(
            os.path.abspath(a.real_yaml)), real_img_path)
    real_res = float(meta["resolution"])
    real_org = meta.get("origin", [0.0, 0.0, 0.0])

    real = load_pgm(real_img_path)
    sim = load_pgm(a.sim)
    print(f"실물 맵 {real.shape[1]}x{real.shape[0]}px  해상도 {real_res}")
    print(f"시뮬 맵 {sim.shape[1]}x{sim.shape[0]}px  해상도 {SIM_RES}")

    # 실물 맵을 시뮬 픽셀 크기로 리샘플한다.
    # 시뮬 1px = SIM_RES(m) = 실물 SIM_RES/SCALE(m) 이므로,
    # 실물 맵에서 그만큼이 몇 px 인지로 비율을 정한다.
    ratio = (SIM_RES / SCALE) / real_res
    if abs(ratio - 1.0) > 0.01:
        nh, nw = int(real.shape[0] / ratio), int(real.shape[1] / ratio)
        real = np.array(Image.fromarray(real).resize((nw, nh), Image.NEAREST))
        print(f"실물 맵 리샘플 -> {nw}x{nh}px  (비율 {1/ratio:.3f})")

    occ_real = occupied(real)
    occ_sim = occupied(sim)
    if not a.use_racks:
        # 목표(시뮬) 쪽만 바깥 벽으로 제한한다. 실물 SLAM 맵은 회전돼 있어
        # 벽이 이미지 테두리에 있지 않으므로 마스크를 씌우면 안 된다.
        m = max(6, int(0.6 / SIM_RES))
        occ_sim = wall_only(occ_sim, m)
        print(f"시뮬 쪽은 바깥 벽만 사용 (테두리 {m}px)")

    src = np.argwhere(occ_real)[:, ::-1].astype(float)   # (x, y)
    if len(src) == 0:
        raise SystemExit("실물 맵에 점유 픽셀이 없습니다. 임계값을 확인하세요.")
    if len(src) > 4000:                                   # 너무 많으면 솎는다
        src = src[np.random.RandomState(0).choice(len(src), 4000, False)]
    dst_pts = np.argwhere(occ_sim)[:, ::-1].astype(float)
    print(f"정합점 실물 {len(src)}개 / 시뮬 {len(dst_pts)}개")

    # 무게중심을 먼저 맞춰 놓고 탐색한다. 안 그러면 이동량을 훑는 범위가
    # 터무니없이 커진다 (맵 크기만큼 훑어야 한다).
    global _PIVOT
    c_src, c_dst = src.mean(axis=0), dst_pts.mean(axis=0)
    _PIVOT = c_src.copy()
    best = (-1.0, 0.0, float(c_dst[0] - c_src[0]), float(c_dst[1] - c_src[1]))
    print(f"무게중심 정렬: dx {best[2]:+.0f}  dy {best[3]:+.0f} px")

    # 거칠게 -> 곱게 (회전은 한 바퀴 전부 훑는다)
    best = search(src, occ_sim, math.pi, 40, math.radians(5), 8, best)
    print(f"  1차  겹침 {best[0]*100:5.1f}%  각 {math.degrees(best[1]):+6.1f}도")
    best = search(src, occ_sim, math.radians(6), 12, math.radians(1), 2, best)
    print(f"  2차  겹침 {best[0]*100:5.1f}%  각 {math.degrees(best[1]):+6.1f}도")
    best = search(src, occ_sim, math.radians(1.2), 2.5,
                  math.radians(0.2), 0.5, best)
    print(f"  3차  겹침 {best[0]*100:5.1f}%  각 {math.degrees(best[1]):+6.2f}도")

    s, th, dx, dy = best

    # 180도 뒤집힌 해도 비슷하게 맞는지 확인한다.
    # 직사각형 방은 180도 돌려도 똑같이 생겨서, 바깥 벽만으로는
    # 앞뒤를 구분할 수 없다. 랙이나 비대칭 표식이 있어야 갈린다.
    flip = search(src, occ_sim, math.radians(3), 8,
                  math.radians(1), 2,
                  (-1.0, th + math.pi, dx, dy))
    if flip[0] > s * 0.9:
        print(f"\n⚠ 180도 뒤집힌 해도 {flip[0]*100:.1f}% 로 비슷합니다 "
              f"(정답 {s*100:.1f}%).")
        print("  바깥 벽만으로는 앞뒤를 구분할 수 없습니다 (직사각형은 180도 대칭).")
        print("  대응책 중 하나가 필요합니다:")
        print("    - 매핑할 때 랙 위에 표식을 올려 라이다에 잡히게 하고 --use-racks")
        print("    - 벽 한 곳에 비대칭 돌출물(각재 등)을 붙이기")
        print("  이대로 두면 AMCL 도 같은 이유로 뒤집힌 위치에 수렴할 수 있습니다.")

    if s < 0.5:
        print("\n⚠ 겹침이 낮습니다. 맵이 많이 다르거나 축척이 안 맞습니다.")
        print("  --use-racks 를 붙이거나, 실물 맵 해상도를 확인하세요.")

    # 픽셀 변환 -> 월드(시뮬) 변환
    H = sim.shape[0]
    # 실물 맵의 (0,0)px 이 시뮬 월드 어디로 가는가
    p0 = rot_translate(np.array([[0.0, 0.0]]), th, dx, dy)[0]
    wx = p0[0] * SIM_RES
    wy = (H - p0[1]) * SIM_RES
    yaw = -th                       # 이미지 y 가 아래로 향하므로 부호 반전

    # yaml 의 origin 은 "이미지 좌하단이 월드 어디인가" 다.
    ph = rot_translate(np.array([[0.0, float(occ_real.shape[0])]]),
                       th, dx, dy)[0]
    ox = ph[0] * SIM_RES / SCALE
    oy = (H - ph[1]) * SIM_RES / SCALE

    print("\n" + "=" * 58)
    print(f"겹침 {s*100:.1f}%   회전 {math.degrees(yaw):+.2f}도")
    print("\nreal_set.yaml 에 이렇게 넣으세요 (실물 좌표계, m):\n")
    print(f"  origin: [{ox:.4f}, {oy:.4f}, {yaw:.4f}]")
    print(f"  resolution: {real_res}")
    print("\n시뮬 좌표로 보려면 x10 하면 됩니다:")
    print(f"  좌하단이 시뮬 ({ox*SCALE:.2f}, {oy*SCALE:.2f}) 에 놓임")
    print("=" * 58)

    if a.preview:
        try:
            rgb = np.zeros((*occ_sim.shape, 3), np.uint8)
            rgb[occupied(sim)] = (80, 80, 80)            # 시뮬 전체 회색
            rgb[occ_sim] = (0, 120, 255)                 # 정합에 쓴 시뮬 벽
            p = rot_translate(src, th, dx, dy)
            r = np.round(p[:, 1]).astype(int)
            c = np.round(p[:, 0]).astype(int)
            ok = ((r >= 0) & (r < rgb.shape[0]) &
                  (c >= 0) & (c < rgb.shape[1]))
            rgb[r[ok], c[ok]] = (255, 60, 60)            # 정합된 실물 벽
            Image.fromarray(rgb).save(a.preview)
            print(f"\n미리보기 저장: {a.preview}  (파랑=시뮬, 빨강=실물)")
        except Exception as e:
            print(f"미리보기 실패: {e}")

    return 0 if s >= 0.5 else 1


if __name__ == "__main__":
    sys.exit(main())
