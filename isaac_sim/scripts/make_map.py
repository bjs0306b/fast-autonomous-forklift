#!/usr/bin/env python3
"""
Generate an occupancy grid (.pgm + .yaml) for the simulated warehouse.

We do NOT run SLAM in simulation. The scene is authored, so the wall positions
are already known - drawing the map directly is faster and exact. SLAM is only
used on the real Orin to map the physical mock-up (owned by D).

Edit WORLD / WALLS / RACKS below to match the Isaac scene, then run:

    python3 make_map.py

Outputs isaac_sim/maps/sim_warehouse.pgm and .yaml next to this script.
No third-party dependencies - the PGM is written as raw P5 binary.
"""

import os
import struct

# ---------------------------------------------------------------------------
# Scene definition - keep in sync with the Isaac scene
#
# Everything is in metres at miniature 1:1 scale (the same scale as D's SLAM
# map), so these numbers can be read straight off the scene.
# ---------------------------------------------------------------------------
RESOLUTION = 0.02          # metres per pixel. 2cm suits a 3x2m warehouse.
ORIGIN = (0.0, 0.0)        # world coords of the map's bottom-left corner

WORLD_W = 3.0              # warehouse width  (m, +X)
WORLD_H = 2.0              # warehouse height (m, +Y)

WALL_THICKNESS = 0.04      # drawn thickness of the perimeter walls (m)

# Interior obstacles as axis-aligned rectangles: (x_min, y_min, x_max, y_max).
# Racks count as obstacles - the vehicle must not drive through them.
RACKS = [
    (2.30, 0.30, 2.50, 0.50),
    (2.30, 0.80, 2.50, 1.00),
    (2.30, 1.30, 2.50, 1.50),
]

# Extra walls / pillars, same format. Leave empty if the perimeter is enough.
WALLS = []

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "maps")
OUT_NAME = "sim_warehouse"


# ---------------------------------------------------------------------------
FREE = 254        # white  - drivable
OCCUPIED = 0      # black  - wall


def world_to_px(x, y, width_px, height_px):
    """World metres -> pixel indices.

    Image rows run top-down while world Y runs bottom-up, so the row index is
    flipped. Getting this wrong is the classic way to end up with a map that is
    mirrored vertically and a robot that drives into walls.
    """
    col = int(round((x - ORIGIN[0]) / RESOLUTION))
    row = height_px - 1 - int(round((y - ORIGIN[1]) / RESOLUTION))
    return clamp(col, 0, width_px - 1), clamp(row, 0, height_px - 1)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def fill_rect(grid, width_px, height_px, rect, value=OCCUPIED):
    x0, y0, x1, y1 = rect
    c0, r1 = world_to_px(x0, y0, width_px, height_px)   # y0 -> lower edge -> larger row
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
        (0.0, 0.0, WORLD_W, t),                    # bottom
        (0.0, WORLD_H - t, WORLD_W, WORLD_H),      # top
        (0.0, 0.0, t, WORLD_H),                    # left
        (WORLD_W - t, 0.0, WORLD_W, WORLD_H),      # right
    ]

    for rect in perimeter + WALLS + RACKS:
        fill_rect(grid, width_px, height_px, rect)

    return grid, width_px, height_px


def write_pgm(path, grid, width_px, height_px):
    with open(path, "wb") as f:
        f.write(b"P5\n")
        f.write(f"{width_px} {height_px}\n255\n".encode())
        f.write(bytes(grid))


def write_yaml(path, pgm_name):
    with open(path, "w") as f:
        f.write(
            f"image: {pgm_name}\n"
            f"resolution: {RESOLUTION}\n"
            f"origin: [{ORIGIN[0]}, {ORIGIN[1]}, 0.0]\n"
            "negate: 0\n"
            "occupied_thresh: 0.65\n"
            "free_thresh: 0.196\n"
        )


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    grid, w, h = build()

    pgm_path = os.path.join(OUT_DIR, f"{OUT_NAME}.pgm")
    yaml_path = os.path.join(OUT_DIR, f"{OUT_NAME}.yaml")

    write_pgm(pgm_path, grid, w, h)
    write_yaml(yaml_path, f"{OUT_NAME}.pgm")

    print(f"{w} x {h} px  ({WORLD_W} x {WORLD_H} m @ {RESOLUTION} m/px)")
    print(f"wrote {pgm_path}")
    print(f"wrote {yaml_path}")


if __name__ == "__main__":
    main()
