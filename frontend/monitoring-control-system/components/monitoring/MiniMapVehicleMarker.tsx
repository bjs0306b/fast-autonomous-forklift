"use client"

import { cn } from "@/lib/utils"
import { toShortLabel } from "@/lib/vehicleStatus"
import { isLoadSafetyStale } from "@/lib/loadSafety"
import type { DashboardVehicle } from "@/types/monitoring"
import type { LoadSafetyRiskLevel, LoadSafetyState } from "@/types/loadSafety"
import { LOAD_SAFETY_RISK_LABEL } from "@/types/loadSafety"
import { VEHICLE_STATUS_COLOR, VEHICLE_STATUS_LABEL } from "./vehicle-status"

/**
 * MiniMapVehicleMarker
 *
 * 미니맵 위의 개별 차량 마커.
 * - 상태별 색상 표시
 * - heading 방향 화살표
 * - REAL / SIMULATION 구분 (테두리 형태)
 * - 선택된 차량 강조
 * - **적재 안전 위험 링 + 배지** (선택하지 않은 차량의 위험도 인지)
 * - 클릭 시 팝업 없이 onSelect(vehicleId) 만 호출
 *
 * <b>위험도를 계산하지 않는다.</b> roll/pitch/forkHeight 를 비교하는 코드가 여기 없는 것은 의도적이다 —
 * 백엔드가 전달한 riskLevel 을 그대로 읽는다. stale 여부도 riskLevel 을 바꾸지 않고 별도 배지로만 알린다.
 */

/**
 * 위험 단계별 마커 표기.
 *
 * <b>색상만으로 구분하지 않는다</b> — 링 + 배지 문자 + aria-label 을 함께 바꾼다.
 *
 * UNKNOWN 에 점선 링을 쓰지 않는 이유: 이 마커는 **점선을 이미 SIMULATION 표식으로 쓰고 있다**
 * (REAL=원형 실선 / SIM=사각 점선). 점선 링을 더하면 "시뮬 차량"과 "판정 불가"가 시각적으로 섞이므로
 * 실선 링 + `?` 배지로 구분한다.
 */
const RISK_MARK: Record<
  LoadSafetyRiskLevel,
  { ring: string | null; badge: string | null; badgeClass: string; pulse: boolean }
> = {
  NORMAL: { ring: null, badge: null, badgeClass: "", pulse: false },
  CAUTION: {
    ring: "border-amber-400/90 border-[1.5px]",
    badge: "△",
    badgeClass: "bg-amber-500 text-amber-950",
    pulse: false,
  },
  WARNING: {
    ring: "border-orange-400 border-2",
    badge: "!",
    badgeClass: "bg-orange-500 text-orange-950",
    pulse: false,
  },
  DANGER: {
    ring: "border-red-500 border-2",
    badge: "!",
    badgeClass: "bg-red-500 text-red-950",
    pulse: true,
  },
  UNKNOWN: {
    ring: "border-slate-400/80 border-[1.5px]",
    badge: "?",
    badgeClass: "bg-slate-400 text-slate-900",
    pulse: false,
  },
}

export function MiniMapVehicleMarker({
  vehicle,
  left,
  top,
  selected,
  loadSafety = null,
  onSelect,
}: {
  vehicle: DashboardVehicle
  left: number
  top: number
  selected: boolean
  /** 이 차량의 최신 적재 안전 상태. 미수신이면 null(기본 마커 유지) */
  loadSafety?: LoadSafetyState | null
  onSelect?: (vehicleId: string) => void
}) {
  const color = VEHICLE_STATUS_COLOR[vehicle.status] ?? VEHICLE_STATUS_COLOR.UNKNOWN
  const statusLabel = VEHICLE_STATUS_LABEL[vehicle.status] ?? vehicle.status
  const isSim = vehicle.source === "SIMULATION"
  const shortLabel = toShortLabel(vehicle.vehicleId)
  const heading = vehicle.location?.heading ?? null

  // 적재 안전: 데이터가 없으면 기본 마커 그대로다(위험 상태로 추정하지 않는다).
  const riskLevel = loadSafety?.riskLevel ?? null
  const mark = riskLevel ? RISK_MARK[riskLevel] : null
  const stale = isLoadSafetyStale(loadSafety)

  return (
    <button
      type="button"
      onClick={() => onSelect?.(vehicle.vehicleId)}
      className="absolute z-10 flex -translate-x-1/2 -translate-y-1/2 flex-col items-center gap-0.5 transition-[left,top] duration-500 ease-out focus:outline-none"
      style={{ left: `${left}%`, top: `${top}%` }}
      aria-label={buildAriaLabel({ shortLabel, isSim, statusLabel, selected, riskLevel, stale })}
      aria-pressed={selected}
    >
      {/* ID 라벨 */}
      <span
        className={cn(
          "rounded px-1 py-px font-mono text-[9px] font-semibold leading-none shadow-sm",
          selected ? "ring-1 ring-white/70" : "",
        )}
        style={{ backgroundColor: "rgba(15,23,42,0.9)", color: color.border }}
      >
        {shortLabel}
      </span>

      <span className="relative flex items-center justify-center">
        {/* 바깥: 적재 안전 위험 링. 선택 glow(안쪽)보다 큰 반지름이라 둘이 겹쳐도 모두 식별된다.
            pointer-events-none 이라 클릭 영역을 가리지 않는다. */}
        {mark?.ring ? (
          <span
            className={cn(
              "pointer-events-none absolute size-9 rounded-full",
              mark.ring,
              // 전체 마커가 아니라 링에만, reduced-motion 에서는 정지
              mark.pulse && "motion-safe:animate-pulse",
            )}
            aria-hidden="true"
          />
        ) : null}

        {/* 중간: 선택 glow */}
        {selected ? (
          <span
            className="pointer-events-none absolute size-7 rounded-full motion-safe:animate-pulse"
            style={{ boxShadow: `0 0 0 2px rgba(${color.glow},0.9), 0 0 14px 3px rgba(${color.glow},0.55)` }}
            aria-hidden="true"
          />
        ) : null}

        {/* heading 방향 화살표. TODO(coordinate): 0도 기준축/회전 방향 미확정 */}
        {typeof heading === "number" ? (
          <span
            className="pointer-events-none absolute -top-1.5 size-0 transition-transform duration-500 ease-out"
            style={{
              transform: `rotate(${heading}deg)`,
              transformOrigin: "center 12px",
              borderLeft: "3px solid transparent",
              borderRight: "3px solid transparent",
              borderBottom: `5px solid ${color.border}`,
            }}
            aria-hidden="true"
          />
        ) : null}

        {/* 안쪽: 마커 본체. REAL=원형, SIMULATION=사각(점선 테두리)로 구분 */}
        <span
          className={cn(
            "relative block size-3.5 border",
            isSim ? "rounded-[2px] border-dashed" : "rounded-full",
          )}
          style={{ backgroundColor: color.base, borderColor: color.border }}
          aria-hidden="true"
        />

        {/* 위험 배지(우상단). 색 외의 식별 수단이다. */}
        {mark?.badge ? (
          <span
            className={cn(
              "pointer-events-none absolute -right-3 -top-3 flex size-3.5 items-center justify-center rounded-full text-[8px] font-bold leading-none shadow-sm",
              mark.badgeClass,
            )}
            aria-hidden="true"
          >
            {mark.badge}
          </span>
        ) : null}

        {/* 지연 배지(우하단). 위험 배지와 자리를 나눠 둘 다 동시에 보이게 한다.
            stale 은 riskLevel 을 바꾸지 않고 "얼마나 오래된 값인지"만 알린다. */}
        {stale ? (
          <span
            className="pointer-events-none absolute -bottom-3 -right-3 flex size-3.5 items-center justify-center rounded-full bg-slate-200 text-[7px] leading-none text-slate-900 shadow-sm"
            aria-hidden="true"
          >
            ⏱
          </span>
        ) : null}
      </span>
    </button>
  )
}

/**
 * 스크린리더용 라벨. 차량 ID·출처·상태에 더해 선택 여부와 적재 위험 상태를 포함한다
 * (아이콘은 전부 aria-hidden 이라 텍스트로 전달되지 않으면 정보가 사라진다).
 *
 * 예: "F01 · 실제 · 이동 중 · 적재 경고 · 데이터 지연 (선택됨)"
 */
function buildAriaLabel({
  shortLabel,
  isSim,
  statusLabel,
  selected,
  riskLevel,
  stale,
}: {
  shortLabel: string
  isSim: boolean
  statusLabel: string
  selected: boolean
  riskLevel: LoadSafetyRiskLevel | null
  stale: boolean
}): string {
  const parts = [shortLabel, isSim ? "시뮬레이션" : "실제", statusLabel]
  if (riskLevel) {
    parts.push(`적재 ${LOAD_SAFETY_RISK_LABEL[riskLevel]}`)
  }
  if (stale) {
    parts.push("데이터 지연")
  }
  return parts.join(" · ") + (selected ? " (선택됨)" : "")
}

export default MiniMapVehicleMarker
