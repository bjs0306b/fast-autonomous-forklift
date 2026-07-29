// 미니어처 T-11 파렛트 — 실물 치수 1/10 (faithful scale)
// 포크가 1/10 실물 파렛트 기준으로 설계됨 → 실물을 그대로 축소.
// 단위 mm. OpenSCAD F6(렌더) 후 F7(Export STL).
//
// ✅ 2026-07-29 실측 반영. 이전 기본값(높이 120·블록 140)은 미검증 표준 근사치였고
//    실물과 달랐다 — 그대로 재출력하면 개구부가 7.6mm로 나와 실물(9.5mm)과 어긋난다.

k = 10;   // 축척비

/* ===== 실물 파렛트 치수(mm) — 2026-07-29 실측 반영 ===== */
R_foot_x   = 1100;   // 가로
R_foot_y   = 1100;   // 세로
R_height   = 140;    // 전체 높이 (미니 14mm 실측)
R_top_deck = 22.5;   // 상판 두께  ┐ 합 45mm는 (높이 140 − 개구 95)에서 역산.
R_bot_deck = 22.5;   // 하판 두께  ┘ 상·하 분배는 실측하지 않아 균등 가정.
R_block    = 133;    // 다리 블록 한 변 — 개구 폭 35mm·중심 간격 48.3mm에서 역산(직접 실측 아님)
/* ===================================================== */

/* ===== 미니어처 지게차 포크 실측(mm) — fit 검증용 ===== */
F_thick = 5;      // 포크 수직 두께(뿌리) → 통로 높이가 이보다 커야 함
                  // ⚠️ 실물 포크는 끝으로 갈수록 얇아지는 테이퍼다. 이 모델엔 반영돼 있지 않아
                  //    진입 초기의 실제 여유는 도면보다 넉넉하다(오픈루프 진입의 근거).
F_width = 12;     // 포크 너비       → 통로 폭이 이보다 커야 함
F_gap   = 40;     // 두 포크 사이 최대 간격(0~40 가변)
/* ==================================================== */

// --- /10 환산 (미니어처) ---
foot_x = R_foot_x  / k;
foot_y = R_foot_y  / k;
height = R_height  / k;
topd   = R_top_deck/ k;
botd   = R_bot_deck/ k;
blk    = R_block   / k;
open_h = height - topd - botd;        // 포크 통로(구멍) 높이  ← 이게 포크 두께보다 커야 함

module deck(z, t) translate([0,0,z]) cube([foot_x, foot_y, t]);
module blocks(z)                       // 3x3 다리 → 4방향 포크 진입
  for (x = [0, foot_x/2 - blk/2, foot_x - blk])
    for (y = [0, foot_y/2 - blk/2, foot_y - blk])
      translate([x, y, z]) cube([blk, blk, open_h]);

if (botd > 0) deck(0, botd);           // 하판
blocks(botd);                          // 다리
deck(botd + open_h, topd);             // 상판

// --- fit 검증 (콘솔에 출력) ---
chan_w  = (foot_x - 3*blk) / 2;        // 포크 통로 폭
chan_sp = (foot_x - blk) / 2;          // 두 통로 중심 간격
fork_cc_max = F_gap + F_width;         // 포크 최대 중심거리 (간격 0 → 최소 = F_width)
echo(str("미니 파렛트: ", foot_x, "x", foot_y, "x", height, "mm"));
echo(str("통로 높이 open_h = ", open_h, "mm  vs  포크두께 ", F_thick,
         "mm  → ", open_h > F_thick ? "OK" : "!! 안 들어감"));
echo(str("통로 폭 chan_w = ", chan_w, "mm  vs  포크너비 ", F_width,
         "mm  → ", chan_w > F_width ? "OK" : "!! 안 들어감"));
echo(str("통로 중심간격 = ", chan_sp, "mm  vs  포크 중심거리 ", F_width, "~", fork_cc_max,
         "mm  → ", (chan_sp >= F_width && chan_sp <= fork_cc_max) ? "OK" : "!! 간격 조정 필요"));
