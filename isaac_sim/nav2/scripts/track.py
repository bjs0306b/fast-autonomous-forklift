"""순환로 기하 — loop_traffic.py 와 fleet_manager.py 가 함께 쓴다.

맵 구조 (sim_warehouse.pgm 실측):
    가운데 x 9~12 는 랙. 좌우 세로 통로(x 3~8 / x 13~18)가 y 2.5~27.7 전 구간
    뚫려 있고, 아래 홀(y 2~7)·위 홀(y 26~28)이 둘을 잇는다.
    따라서 랙을 둘러싸는 둘레 약 67 m 순환로를 만들 수 있다.

        y=27  (5,27) ◀───── 위 홀 ───── (15.5,27)
                 │                          ▲
                 │ 왼쪽 통로     [랙]        │ 오른쪽 통로
                 ▼                          │
        y=4   (5,4) ────── 아래 홀 ────▶ (15.5,4)

앞뒤 판정은 x 좌표가 아니라 '순환로를 따라간 거리(호장 s)' 로 한다.
세로 구간이 있어 x 만으로는 앞뒤를 알 수 없다.
"""
import math

X_LEFT, X_RIGHT = 5.0, 15.5      # 좌/우 세로 통로
Y_BOT, Y_TOP = 4.0, 27.0         # 아래/위 가로 홀


def build_corners(direction):
    """순환 방향에 맞춘 모서리 순서.

      CCW (반시계): 아래 +x → 오른쪽 +y → 위 -x → 왼쪽 -y
      CW  (시계)  : 아래 -x → 왼쪽 +y   → 위 +x → 오른쪽 -y
    """
    if str(direction).upper() == "CW":
        return [(X_LEFT, Y_BOT), (X_LEFT, Y_TOP),
                (X_RIGHT, Y_TOP), (X_RIGHT, Y_BOT)]
    return [(X_RIGHT, Y_BOT), (X_RIGHT, Y_TOP),
            (X_LEFT, Y_TOP), (X_LEFT, Y_BOT)]


class Track:
    """모서리들을 이은 닫힌 경로. 위치를 '따라간 거리 s' 로 다룬다."""

    def __init__(self, corners):
        self.corners = corners
        self.segs = []          # (시작점, 끝점, 길이)
        self.corner_s = []      # 각 모서리의 s
        acc = 0.0
        n = len(corners)
        for i in range(n):
            a, b = corners[i], corners[(i + 1) % n]
            L = math.dist(a, b)
            self.corner_s.append(acc)
            self.segs.append((a, b, L))
            acc += L
        self.length = acc

    def project(self, p):
        """p 를 경로에 사영 -> (따라간 거리 s, 경로에서 벗어난 거리)."""
        best_d, best_s = float("inf"), 0.0
        acc = 0.0
        for a, b, L in self.segs:
            if L < 1e-6:
                continue
            vx, vy = b[0] - a[0], b[1] - a[1]
            t = ((p[0] - a[0]) * vx + (p[1] - a[1]) * vy) / (L * L)
            t = max(0.0, min(1.0, t))
            cx, cy = a[0] + t * vx, a[1] + t * vy
            d = math.hypot(p[0] - cx, p[1] - cy)
            if d < best_d:
                best_d, best_s = d, acc + t * L
            acc += L
        return best_s, best_d

    def point_at(self, s):
        """거리 s 지점의 (x, y, 진행 방향)."""
        s %= self.length
        acc = 0.0
        for a, b, L in self.segs:
            if L < 1e-6:
                continue
            if s <= acc + L:
                t = (s - acc) / L
                return (a[0] + t * (b[0] - a[0]),
                        a[1] + t * (b[1] - a[1]),
                        math.atan2(b[1] - a[1], b[0] - a[0]))
            acc += L
        a, b, _ = self.segs[-1]
        return b[0], b[1], math.atan2(b[1] - a[1], b[0] - a[0])

    def gap(self, s_from, s_to):
        """진행 방향으로 s_from 에서 s_to 까지 남은 거리."""
        return (s_to - s_from) % self.length

    def corner_pose(self, i):
        """모서리 i 의 (x, y, 그 모서리를 돈 뒤 진행할 방향)."""
        a = self.corners[i]
        b = self.corners[(i + 1) % len(self.corners)]
        return a[0], a[1], math.atan2(b[1] - a[1], b[0] - a[0])

    def next_corner(self, s_me, tol=1.5):
        """진행 방향으로 가장 가까운 다음 모서리 번호."""
        gaps = []
        for i, cs in enumerate(self.corner_s):
            g = self.gap(s_me, cs)
            gaps.append((g if g > tol else g + self.length, i))
        return min(gaps)[1]
