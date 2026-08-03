import {
  AISLE_GUIDES,
  GRID_STEP_M,
  START_ZONE,
  WAREHOUSE_RACKS,
  WAREHOUSE_WALLS,
} from "@/lib/config/warehouseLayout"
import { WAREHOUSE_WORLD_BOUNDS } from "@/lib/config/warehouseMap"

/**
 * 창고 평면도 배경.
 *
 * 400x600 PNG(`sim_warehouse.png`) 대신 SVG 로 그린다. 이유:
 *   - PNG 는 라이다 점유격자를 그대로 래스터화한 것이라 흰 바닥·검은 막대뿐이고, 확대하면 계단이 진다.
 *   - 랙/통로/구역을 **구분해서** 보여 주려면 도형이 개별 요소여야 한다(PNG 는 한 장의 픽셀 덩어리다).
 *   - 좌표가 월드 단위 그대로 남아 마커 좌표계와 어긋날 여지가 없다.
 *
 * **viewBox 가 곧 월드 좌표계다.** `0 0 20 30`(= WAREHOUSE_WORLD_BOUNDS)이고 Y 는 아래로 증가하는
 * SVG 관례라, 그룹 전체에 `translate(0,30) scale(1,-1)` 을 걸어 월드 좌표(Y 위로 증가)를 그대로 쓴다.
 * 덕분에 이 파일 안의 모든 좌표는 obstacles.txt 의 숫자와 1:1 로 같다.
 *
 * `preserveAspectRatio="none"` 을 쓰지만 왜곡되지 않는다 — 부모(MiniMap)가 컨테이너 비율을
 * 20:30 으로 고정하기 때문이다. 이 방식이라야 SVG 렌더 영역과 마커의 % 기준 영역이 정확히 일치한다.
 */
export function WarehouseMapSvg({ className }: { className?: string }) {
  const { minX, maxX, minY, maxY } = WAREHOUSE_WORLD_BOUNDS
  const width = maxX - minX
  const height = maxY - minY

  const gridXs: number[] = []
  for (let x = minX + GRID_STEP_M; x < maxX; x += GRID_STEP_M) gridXs.push(x)
  const gridYs: number[] = []
  for (let y = minY + GRID_STEP_M; y < maxY; y += GRID_STEP_M) gridYs.push(y)

  return (
    <svg
      className={className}
      viewBox={`${minX} ${minY} ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label="창고 평면도"
    >
      {/* 월드 좌표(Y 위로 증가)를 그대로 쓰기 위한 축 반전 */}
      <g transform={`translate(0, ${maxY + minY}) scale(1, -1)`}>
        {/* 주행 가능 바닥 */}
        <rect x={minX} y={minY} width={width} height={height} fill="#0f1a2b" />

        {/* 좌표 그리드 — 5m 간격, 아주 옅게 */}
        <g stroke="#38bdf8" strokeWidth={0.03} opacity={0.14}>
          {gridXs.map((x) => (
            <line key={`gx-${x}`} x1={x} y1={minY} x2={x} y2={maxY} />
          ))}
          {gridYs.map((y) => (
            <line key={`gy-${y}`} x1={minX} y1={y} x2={maxX} y2={y} />
          ))}
        </g>

        {/* 주행 통로 가이드 — 계산된 표시선이라 가장 옅게 */}
        <g stroke="#7dd3fc" strokeWidth={0.06} strokeDasharray="0.5 0.6" opacity={0.16}>
          {AISLE_GUIDES.map((guide, index) => (
            <line
              key={`aisle-${index}`}
              x1={guide.x1}
              y1={guide.y1}
              x2={guide.x2}
              y2={guide.y2}
            />
          ))}
        </g>

        {/* 랙 — 본체 + 베이 구분선 */}
        {WAREHOUSE_RACKS.map((rack) => {
          const rackYMin = rack.bayBoundaries[0]
          const rackYMax = rack.bayBoundaries[rack.bayBoundaries.length - 1]
          return (
            <g key={rack.id}>
              <rect
                x={rack.xMin}
                y={rackYMin}
                width={rack.xMax - rack.xMin}
                height={rackYMax - rackYMin}
                fill="#334155"
                fillOpacity={0.85}
                stroke="#64748b"
                strokeWidth={0.06}
              />
              {/* 베이(선반 칸) 구분선 */}
              <g stroke="#94a3b8" strokeWidth={0.05} opacity={0.55}>
                {rack.bayBoundaries.slice(1, -1).map((y) => (
                  <line key={`${rack.id}-bay-${y}`} x1={rack.xMin} y1={y} x2={rack.xMax} y2={y} />
                ))}
              </g>
            </g>
          )
        })}

        {/* 외벽 */}
        <g fill="#475569" stroke="#94a3b8" strokeWidth={0.03}>
          {WAREHOUSE_WALLS.map((wall, index) => (
            <rect
              key={`wall-${index}`}
              x={wall.xMin}
              y={wall.yMin}
              width={wall.xMax - wall.xMin}
              height={wall.yMax - wall.yMin}
            />
          ))}
        </g>

        {/* 창고 외곽 경계 — 두꺼운 검은 선 대신 얇고 선명한 한 겹 */}
        <rect
          x={minX}
          y={minY}
          width={width}
          height={height}
          fill="none"
          stroke="#38bdf8"
          strokeWidth={0.1}
          opacity={0.5}
        />

        {/* 출발 지점 */}
        <g opacity={0.75}>
          <circle
            cx={START_ZONE.x}
            cy={START_ZONE.y}
            r={0.9}
            fill="none"
            stroke="#38bdf8"
            strokeWidth={0.08}
            strokeDasharray="0.4 0.35"
          />
          <circle cx={START_ZONE.x} cy={START_ZONE.y} r={0.12} fill="#38bdf8" />
        </g>
      </g>

      {/* 텍스트는 축 반전 밖에 둔다(반전 안에 두면 글자가 뒤집힌다).
          y 는 화면 좌표라 (maxY - 월드y) 로 직접 계산한다. */}
      <g
        fill="#cbd5e1"
        fontSize={0.62}
        fontFamily="ui-monospace, SFMono-Regular, Menlo, monospace"
        opacity={0.85}
      >
        {WAREHOUSE_RACKS.map((rack) => {
          const rackYMax = rack.bayBoundaries[rack.bayBoundaries.length - 1]
          return (
            <text
              key={`${rack.id}-label`}
              x={(rack.xMin + rack.xMax) / 2}
              y={maxY - rackYMax - 0.35}
              textAnchor="middle"
            >
              {rack.label}
            </text>
          )
        })}
        <text
          x={START_ZONE.x}
          y={maxY - START_ZONE.y - 1.25}
          textAnchor="middle"
          fill="#7dd3fc"
          fontSize={0.55}
        >
          {START_ZONE.label}
        </text>
      </g>

      {/* 좌표 눈금 — 왼쪽 아래 모서리에만 작게 */}
      <g fill="#64748b" fontSize={0.5} fontFamily="ui-monospace, SFMono-Regular, Menlo, monospace">
        {gridXs.map((x) => (
          <text key={`gxl-${x}`} x={x + 0.15} y={maxY - 0.25}>
            {x}
          </text>
        ))}
        {gridYs.map((y) => (
          <text key={`gyl-${y}`} x={0.25} y={maxY - y - 0.2}>
            {y}
          </text>
        ))}
      </g>
    </svg>
  )
}

export default WarehouseMapSvg
