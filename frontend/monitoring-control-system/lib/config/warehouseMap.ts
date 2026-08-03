// 창고 맵(미니맵 배경)과 월드 좌표계 설정.
//
// 값의 근거는 시뮬 맵 생성 스크립트 `isaac_sim/nav2/make_map.py` 다. 그 파일이 맵 이미지를
// 만들어 낸 정의이므로, 화면 좌표 변환도 같은 정의를 따라야 마커가 배경과 어긋나지 않는다.
//
//   WORLD_W = 20.0   창고 폭   (m, +X)
//   WORLD_H = 30.0   창고 깊이 (m, +Y)
//   RESOLUTION = 0.05 m/px  →  400 x 600 px  (public/images/warehouse-map.png 와 동일)
//   ORIGIN = (0, 0)  맵 "왼쪽아래" 구석의 세계 좌표
//
// 그 스크립트의 world_to_px() 는 다음과 같이 행을 뒤집는다.
//   col = (x - originX) / resolution
//   row = (height - 1) - (y - originY) / resolution
// 즉 **세계 Y 는 위로 증가하고 이미지 행은 아래로 증가**한다 → invertY = true 가 맞다.
// (make_map.py 주석: "이걸 틀리면 맵이 상하로 뒤집혀서 지게차가 벽으로 돌진한다")
//
// TODO(map-bounds): 위 값은 **시뮬(10배 세계) 기준**이다. 실물 미니어처는 1/10 축척이라
//   `ros2_ws/maps/sim_warehouse_real.yaml` 의 resolution 이 0.005(= 2m x 3m)다.
//   실물 차량이 MQTT 로 보내는 좌표가 시뮬 기준(20x30)인지 실물 기준(2x3)인지는
//   코드로 확인되지 않는다 → ROS2/Isaac 담당 확인 필요. 실물 기준이면 bounds 를
//   {maxX: 2, maxY: 3} 으로 바꾸거나 발행 경계에서 x10 변환해야 한다.
// TODO(heading): heading 0도의 기준축(+X 가정)과 회전 방향(반시계 가정)도 미확정이다.
//   `headingToMarkerRotation()` 주석 참고.

import type { WorldBounds } from "@/lib/coordinate"

/** 미니맵 배경으로 사용하는 창고 맵 이미지(원본: prompt/prompt/sim_warehouse (1).png). */
export const WAREHOUSE_MAP_IMAGE = "/images/warehouse-map.png"

/** 배경 이미지의 픽셀 크기. 컨테이너를 이 비율로 고정해 마커와 배경이 어긋나지 않게 한다. */
export const WAREHOUSE_MAP_IMAGE_SIZE = { width: 400, height: 600 } as const

/** 창고 월드 좌표 범위(m). make_map.py 의 WORLD_W/WORLD_H/ORIGIN 과 일치시킨다. */
export const WAREHOUSE_WORLD_BOUNDS: WorldBounds = {
  minX: 0,
  maxX: 20,
  minY: 0,
  maxY: 30,
}

/**
 * 세계 Y축 증가 방향이 화면 아래가 아니라 위인지 여부.
 * make_map.py 가 행을 뒤집어 이미지를 만들었으므로 true 다.
 */
export const WAREHOUSE_INVERT_Y = true

/**
 * 위치 데이터를 아직 한 번도 받지 못한 차량의 초기 표시 위치(월드 좌표, m).
 *
 * 값의 출처는 `isaac_sim/nav2/maps/sim_warehouse_labeled.png` 다. 그 이미지의 빨간 표식 옆에
 * **"START (3,2) ->+X"** 라고 적혀 있어, 좌표와 방향이 모두 문서화돼 있다.
 *
 * **퍼센트를 컴포넌트에 직접 쓰지 않고 월드 좌표로 두는 이유**는, 실제 위치가 들어왔을 때와
 * 완전히 같은 변환 경로(worldToPercent)를 타게 하기 위해서다.
 * 참고로 이 좌표를 변환하면 left = 3/20 = 15%, top = 100 - 2/30*100 ≈ 93.3% 이다.
 *
 * 여기에 없는 차량은 위치를 받기 전까지 미니맵에 표시하지 않는다.
 */
export const INITIAL_VEHICLE_POSES: Record<
  string,
  { x: number; y: number; heading: number }
> = {
  "REAL-F01": { x: 3, y: 2, heading: 0 },
}

/**
 * 월드 heading(degree)을 마커 화살표의 CSS 회전값(degree)으로 바꾼다.
 *
 * 기준 두 가지
 *   1. heading 0도 = +X(화면 오른쪽)
 *      → `isaac_sim/nav2/maps/sim_warehouse_labeled.png` 의 "START (3,2) **->+X**" 표기와
 *        오른쪽을 향한 화살표가 근거다.
 *   2. heading 이 커지면 반시계 방향(수학 표준, ROS2 yaw 관례)
 *      → `ros2_ws/.../dto.py#quaternion_to_heading` 이 atan2 기반 yaw 를 degree 로 바꾼다는 점에서
 *        반시계로 보이나, 실차로 확인된 값은 아니다(`추정`).
 *
 * 마커 화살표는 회전 0에서 **위쪽(-Y 화면 방향)** 을 향하도록 그린다. 화면 위쪽은 월드 +Y,
 * 즉 heading 90도에 해당한다. CSS rotate 는 시계 방향이 양수이므로 부호가 뒤집힌다.
 *
 *   rotation = 90 - heading
 *
 * 검산: heading 0(+X, 오른쪽) → 90deg(화살표가 오른쪽), heading 90(+Y, 위) → 0deg(위),
 *       heading 180(-X, 왼쪽) → -90deg(왼쪽).
 */
export function headingToMarkerRotation(heading: number): number {
  return 90 - heading
}
