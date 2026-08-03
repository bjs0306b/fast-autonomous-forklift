// 창고 내부 배치(벽·랙·통로)를 **월드 좌표(m)** 로 정의한다.
//
// ─────────────────────────────────────────────────────────────────────────────
// 출처 — 임의로 그린 도형이 아니다.
//
//   isaac_sim/nav2/maps/obstacles.txt      라이다 스캔면(1.2m)에서 실제로 빔을 막는 장애물의
//                                          (x_min, y_min, x_max, y_max) 목록. 시뮬 씬(ff.usd)에서
//                                          추출한 값이라 Nav2 가 쓰는 점유격자와 동일하다.
//   isaac_sim/nav2/make_map.py             WORLD_W=20, WORLD_H=30, ORIGIN=(0,0), RESOLUTION=0.05
//   isaac_sim/nav2/maps/sim_warehouse_labeled.png
//                                          위 맵에 축 눈금과 "START (3,2) ->+X" 를 표기한 설계 확인용 이미지
//
// obstacles.txt 는 라이다 관점의 원시 사각형 목록이라 같은 랙이 여러 조각으로 쪼개져 있다.
// 화면에는 조각이 아니라 **구조물 단위**로 보여야 읽히므로, 아래에서 조각들을 벽/랙으로 묶었다.
// 좌표는 반올림 없이 원본 그대로 쓴다(그리는 방식만 바꾸고 위치는 바꾸지 않는다).
// ─────────────────────────────────────────────────────────────────────────────

/** 월드 좌표계의 축 정렬 사각형(m). obstacles.txt 의 한 줄과 같은 형식이다. */
export interface WorldRect {
  xMin: number
  yMin: number
  xMax: number
  yMax: number
}

/**
 * 외벽. obstacles.txt 의 벽 조각을 이어 붙인 것이다.
 *
 * 원본에서 아래·위 벽은 x 방향으로 끊겨 있다(중앙부에 조각이 없다). 라이다에 잡히지 않는
 * 출입구로 보이지만 **문이라고 단정할 근거가 코드에 없어**, 있는 조각만 그대로 그린다.
 */
export const WAREHOUSE_WALLS: WorldRect[] = [
  // 좌측 벽 (x -0.08 ~ 0.12, y 0.05 ~ 30.25 를 조각으로 나눠 스캔한 것)
  { xMin: -0.08, yMin: 0.05, xMax: 0.12, yMax: 30.25 },
  // 우측 벽 (x 19.46 ~ 19.67)
  { xMin: 19.46, yMin: 0.05, xMax: 19.67, yMax: 30.25 },
  // 아래 벽 — 좌·우 조각만 존재한다
  { xMin: -0.08, yMin: 0.05, xMax: 2.92, yMax: 0.25 },
  { xMin: 16.67, yMin: 0.05, xMax: 19.67, yMax: 0.25 },
  // 위 벽 — 좌·우 조각 + 중앙의 작은 조각 3개
  { xMin: -0.08, yMin: 30.05, xMax: 2.92, yMax: 30.25 },
  { xMin: 16.66, yMin: 30.05, xMax: 19.66, yMax: 30.25 },
  { xMin: 3.78, yMin: 30.13, xMax: 4.37, yMax: 30.25 },
  { xMin: 5.2, yMin: 30.14, xMax: 5.79, yMax: 30.26 },
  { xMin: 5.91, yMin: 29.84, xMax: 6.45, yMax: 30.25 },
]

/** 랙 한 열. bays 는 obstacles.txt 의 4개 블록 경계(y)를 그대로 쓴다. */
export interface WarehouseRack {
  id: string
  label: string
  xMin: number
  xMax: number
  /** 각 베이의 y 경계. 길이 N 이면 베이는 N-1 개다. */
  bayBoundaries: number[]
}

/**
 * 랙 2열. 둘 다 y 8.55 ~ 24.55 구간을 4개 베이로 나눈다(obstacles.txt 의 블록 경계와 동일).
 * 라벨은 화면 가독용이며 백엔드 slot_code 와는 아직 연결돼 있지 않다(§보고서 참고).
 */
export const WAREHOUSE_RACKS: WarehouseRack[] = [
  {
    id: "RACK-A",
    label: "RACK A",
    xMin: 0.39,
    xMax: 1.47,
    bayBoundaries: [8.55, 12.55, 16.55, 20.55, 24.55],
  },
  {
    id: "RACK-B",
    label: "RACK B",
    xMin: 9.71,
    xMax: 10.79,
    bayBoundaries: [8.55, 12.55, 16.55, 20.55, 24.55],
  },
]

/**
 * 주행 통로 가이드. **장애물 데이터가 아니라 랙 사이 빈 공간의 중심선을 계산한 표시용 선**이다.
 * Nav2 가 이 선을 따라 간다는 뜻이 아니므로 아주 옅게만 그린다(경로는 /topic/vehicles/path 가 별도).
 */
export const AISLE_GUIDES: { x1: number; y1: number; x2: number; y2: number }[] = [
  // RACK-A 와 RACK-B 사이 (x 1.47 ~ 9.71 의 중앙)
  { x1: 5.59, y1: 1.5, x2: 5.59, y2: 28.5 },
  // RACK-B 오른쪽 개활 구역 (x 10.79 ~ 19.46 의 중앙)
  { x1: 15.13, y1: 1.5, x2: 15.13, y2: 28.5 },
  // 랙 아래·위를 잇는 가로 통로 (랙 y 범위 8.55~24.55 바깥)
  { x1: 1.5, y1: 5.5, x2: 18.5, y2: 5.5 },
  { x1: 1.5, y1: 27, x2: 18.5, y2: 27 },
]

/**
 * 출발 지점. sim_warehouse_labeled.png 에 "START (3,2) ->+X" 로 표기돼 있다.
 * 이 값이 heading 0도 = +X 라는 근거이기도 하다(lib/config/warehouseMap.ts 참고).
 */
export const START_ZONE = { x: 3, y: 2, label: "START" } as const

/** 좌표 그리드 간격(m). labeled 이미지의 눈금과 같은 5m 간격을 쓴다. */
export const GRID_STEP_M = 5

/**
 * 프로젝트 데이터가 없어 **표시하지 않는** 구역들.
 *
 * 측정 스테이션·적재 대기 구역·목적지 슬롯·제한 구역은 프론트가 읽을 수 있는 좌표 소스가 없다.
 * `storage_slot` 테이블에 접근 좌표(destination_x/y)가 있지만 이를 내려주는 REST API 가 없다
 * (`GET /api/storage-slots` 부재). 없는 구역을 그럴듯하게 그리면 관제 화면이 실제와 다른 배치를
 * 사실처럼 보여 주므로, 데이터가 생기기 전까지 그리지 않는다.
 */
export const UNAVAILABLE_ZONES = [
  "측정 스테이션",
  "적재 대기 구역",
  "목적지 슬롯",
  "제한 구역",
] as const
