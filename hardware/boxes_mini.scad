// 미니어처 화물 박스 (실물 /10) — 프린트용
// 단위 mm. OpenSCAD F6(렌더) 후 F7(Export STL).
// 편하중은 기하 중심으로 판정 → 솔리드로 뽑아도 무방(shell=0).

scale_k = 10;          // 축척비

// 실물 치수 mm [W, D, H]
box_2   = [184, 267, 152];   // 2호  x1
box_2_1 = [342, 255, 105];   // 2-1호 x2

shell   = 0;           // 0 = 솔리드. >0이면 그 두께(mm)로 속 빈 셸(필라멘트 절감)
gap     = 8;           // 베드에서 부품 간 간격

module mini_box(real_mm) {
  d = [ for (v = real_mm) v/scale_k ];
  if (shell <= 0)
    cube(d);
  else
    difference() {
      cube(d);
      translate([shell, shell, shell])
        cube([d[0]-2*shell, d[1]-2*shell, d[2]-2*shell]); // 윗면 열린 셸
    }
}

// 한 베드에 3개(2호 1 + 2-1호 2) 나란히 배치
mini_box(box_2);
translate([box_2[0]/scale_k + gap, 0, 0]) mini_box(box_2_1);
translate([box_2[0]/scale_k + box_2_1[0]/scale_k + 2*gap, 0, 0]) mini_box(box_2_1);
