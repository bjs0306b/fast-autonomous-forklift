"use client"

import { Ban, Loader2, OctagonAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { formatClockTime, formatNumber } from "@/lib/format"
import { useNow } from "@/hooks/useNow"
import {
  getLocationFreshness,
  isLocationOutdated,
  type LocationFreshnessLevel,
} from "@/lib/monitoring/locationFreshness"
import type { DashboardVehicle } from "@/types/monitoring"
import { MeasurementFailureCard } from "./MeasurementFailureCard"
import { SelectedVehicleInfoPanel } from "./SelectedVehicleInfoPanel"

/**
 * VehicleDetailPanel
 *
 * 미니맵에서 선택한 차량의 상세 정보를 표시하고,
 * 핵심 상태, 정밀 좌표, 작업 정보와 일반 STOP/개별 EMERGENCY STOP 제어를 제공한다.
 *
 * 표시 값은 전부 props 로 내려온 selectedVehicle 에서만 읽는다 —
 * 상태/위치 WebSocket 이벤트가 도착하면 상위 상태가 갱신되어 이 패널도 자동으로 다시 그려진다.
 *
 * NOTE(FR-503): EMERGENCY STOP 버튼은 실제 API 에 연결돼 있다.
 *
 * 일반 STOP과 EMERGENCY STOP은 기능이 다른 기존 API를 그대로 사용하며 위험 제어 영역에 모은다.
 */
export function VehicleDetailPanel({
  vehicle,
  onStop,
  onEmergencyStop,
  stopPending = false,
  emergencyStopPending = false,
  className,
}: {
  vehicle: DashboardVehicle | null
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
  /** 이 차량의 일반 정지 요청이 진행 중인지(중복 클릭 방지) */
  stopPending?: boolean
  /** 이 차량의 비상정지 요청이 진행 중인지(중복 클릭 방지) */
  emergencyStopPending?: boolean
  className?: string
}) {
  return (
    <section
      className={cn(
        "flex h-full min-h-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]",
        className,
      )}
      aria-label="선택 차량 상세 정보"
    >
      <header className="flex shrink-0 items-center justify-between border-b border-slate-800 px-3 py-1.5">
        <span className="text-xs font-semibold text-slate-100">차량 상세</span>
      </header>

      {/* 미선택이어도 같은 레이아웃을 그대로 그리고 값만 "-" 로 채운다. 안내 문구 하나로
          갈아 끼우면 어떤 항목이 있는지조차 안 보여, 차량을 고른 뒤에야 화면 구조를 알게 된다. */}
      <VehicleDetailContent
        vehicle={vehicle}
        onStop={onStop}
        onEmergencyStop={onEmergencyStop}
        stopPending={stopPending}
        emergencyStopPending={emergencyStopPending}
      />
    </section>
  )
}

function VehicleDetailContent({
  vehicle,
  onStop,
  onEmergencyStop,
  stopPending,
  emergencyStopPending,
}: {
  /** null 이면 아직 아무 차량도 클릭하지 않은 상태다. 모든 값이 "-" 로 표시된다. */
  vehicle: DashboardVehicle | null
  onStop?: (vehicleId: string) => void
  onEmergencyStop?: (vehicleId: string) => void
  stopPending: boolean
  emergencyStopPending: boolean
}) {
  const loc = vehicle?.location
  // X 와 Y 는 서로 독립적으로 판정한다. 예전에는 둘 다 있을 때만 값을 냈는데, 한쪽만 들어온
  // 차량이 좌표를 통째로 못 받은 것처럼 보였다. 0 은 정상 좌표이므로 truthy 가 아니라 != null 로 본다.
  const xText = loc?.x != null ? formatNumber(loc.x, 2) : "-"
  const yText = loc?.y != null ? formatNumber(loc.y, 2) : "-"

  // 새 위치 이벤트가 오지 않아도 경과 시간은 늘어나야 한다 — 통신이 끊긴 상황이 정확히 그렇다.
  const now = useNow()
  const freshness = getLocationFreshness(loc?.receivedAt ?? null, now)
  // 값이 낡았을 때 위치·Heading·Speed 를 "지금 값"으로 읽지 않도록 같은 톤으로 표시한다.
  // 숫자 옆에서 경고해야 의미가 있다 — 수신 시간 칸만 바꾸면 그 칸을 안 본 사람은 그대로 오해한다.
  const outdated = vehicle != null && isLocationOutdated(freshness.level)
  const valueTone: FieldTone = outdated ? toneOf(freshness.level) : "default"
  const outdatedTitle = outdated
    ? `${freshness.ageLabel} 수신된 값입니다. 현재 위치와 다를 수 있습니다.`
    : undefined

  // 이미 ESTOP 상태면 재발행을 막는다. 이 판단은 오직 차량이 보고한 status 로만 한다 —
  // 명령 발행 성공(PUBLISHED)으로는 절대 ESTOP 으로 간주하지 않는다.
  const alreadyEstopped = vehicle?.status === "ESTOP"
  // 선택된 차량이 없으면 보낼 대상이 없다. 제어 버튼은 비활성이어야 한다 —
  // 활성인 채로 두면 "어느 차량을 세우는 건지" 모르는 명령이 나갈 수 있다.
  const stopDisabled = !vehicle || stopPending || emergencyStopPending || !onStop
  const estopDisabled =
    !vehicle || alreadyEstopped || emergencyStopPending || stopPending || !onEmergencyStop
  const estopLabel = emergencyStopPending
    ? "명령 전송 중..."
    : alreadyEstopped
      ? "비상정지 상태"
      : vehicle
        ? `${vehicle.vehicleId} 비상정지`
        : "비상정지"

  return (
    <div className="flex min-h-0 flex-1 flex-col p-2">
      {/* 상세 정보만 내부 스크롤을 허용한다. 제어 버튼은 항상 패널 하단에 남는다. */}
      <div className="min-h-0 flex-1 space-y-2 overflow-y-auto overscroll-contain pr-0.5">
        {/* ID · 상태 · 화물 높이 · 적재 여부를 반응형 핵심 그리드로 한 번만 표시한다. */}
        <SelectedVehicleInfoPanel vehicle={vehicle} />

        {/* 실패 원인은 스크롤 영역 최상단에 둔다 — 작업자가 가장 먼저 봐야 할 정보다.
            실패가 없거나 차량 미선택이면 아무것도 렌더링되지 않아 기존 레이아웃이 그대로 유지된다. */}
        <MeasurementFailureCard failure={vehicle?.lastFailure} />

        {/* 위치는 X·Y 를 각각 별도 요소로 그린다 — 한 문자열로 합치면 칸이 좁을 때 뒤쪽(Y)이
            통째로 말줄임표로 잘려 좌표를 읽을 수 없다. */}
        <div>
          <dl className="grid grid-cols-1 gap-1.5 text-xs sm:grid-cols-2 xl:grid-cols-4">
            <PositionField
              xText={xText}
              yText={yText}
              tone={valueTone}
              title={outdatedTitle}
            />
            <DetailField
              label="Heading (°)"
              value={loc?.heading != null ? formatNumber(loc.heading, 1) : "—"}
              mono
              tone={valueTone}
              title={outdatedTitle}
            />
            <DetailField
              label="Speed (m/s)"
              value={loc?.speed != null ? formatNumber(loc.speed, 2) : "—"}
              mono
              tone={valueTone}
              title={outdatedTitle}
            />
            {/* 시각만 보여주면 "몇 분 전인지"를 사용자가 벽시계와 빼서 계산해야 한다.
                경과 시간과 상태 문구를 같은 칸에 붙여 한눈에 읽히게 한다. */}
            <DetailField
              label="최근 수신 시간"
              value={formatClockTime(loc?.receivedAt ?? null)}
              mono
              tone={vehicle ? toneOf(freshness.level) : "default"}
              // 수신 기록이 없으면 "— · —" 가 되므로 경과 시간은 생략하고 배지만 남긴다.
              suffix={vehicle && freshness.level !== "missing" ? freshness.ageLabel : undefined}
              badge={vehicle ? freshness.statusLabel : null}
              testId="vehicle-location-freshness"
              level={freshness.level}
            />
          </dl>
        </div>
      </div>

      {/* 제어 버튼은 스크롤 영역 밖의 고정 행이다. */}
      <div className="mt-2 grid shrink-0 grid-cols-1 gap-1.5 border-t border-slate-800 pt-2 sm:grid-cols-2">
        <button
          type="button"
          onClick={() => vehicle && onStop?.(vehicle.vehicleId)}
          disabled={stopDisabled}
          aria-label={vehicle ? `${vehicle.vehicleId} 차량 일반 정지` : "차량 일반 정지 (차량 미선택)"}
          aria-busy={stopPending}
          data-testid="selected-vehicle-stop-button"
          className={cn(
            "inline-flex items-center justify-center gap-1.5 rounded-md border px-3 py-1.5 text-xs font-semibold transition-colors focus-visible:ring-2 focus-visible:ring-amber-300 focus-visible:outline-none",
            stopDisabled
              ? "cursor-not-allowed border-slate-700 bg-slate-800/50 text-slate-500"
              : "border-amber-500/50 bg-amber-950/40 text-amber-100 hover:bg-amber-900/50",
          )}
        >
          {stopPending ? (
            <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          ) : (
            <Ban className="size-4" aria-hidden="true" />
          )}
          {stopPending ? "정지 명령 전송 중" : "일반 정지"}
        </button>
        <button
          type="button"
          onClick={() => vehicle && onEmergencyStop?.(vehicle.vehicleId)}
          disabled={estopDisabled}
          aria-busy={emergencyStopPending}
          data-testid="vehicle-estop-button"
          className={cn(
            "inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1.5 text-xs font-semibold text-white transition-colors focus-visible:ring-2 focus-visible:ring-red-300 focus-visible:outline-none",
            estopDisabled
              ? "cursor-not-allowed bg-red-900/60 text-red-200/70"
              : "bg-red-600 hover:bg-red-500",
          )}
          title={alreadyEstopped ? "이미 비상정지 상태입니다" : undefined}
        >
          <OctagonAlert className="size-4" aria-hidden="true" />
          {estopLabel}
        </button>
      </div>
    </div>
  )
}

/**
 * 값 칸의 강조 톤. 새 색상 체계를 만들지 않고 프로젝트가 이미 쓰는
 * amber(경고) / red(위험) 조합을 그대로 따른다(MeasurementFailureCard 와 동일).
 */
type FieldTone = "default" | "warning" | "danger"

function toneOf(level: LocationFreshnessLevel): FieldTone {
  if (level === "stale") return "danger"
  if (level === "delayed") return "warning"
  return "default"
}

const TONE_STYLE: Record<FieldTone, { container: string; value: string; badge: string }> = {
  default: { container: "bg-white/5", value: "text-slate-100", badge: "" },
  warning: {
    container: "bg-amber-950/30 ring-1 ring-amber-500/40",
    value: "text-amber-100",
    badge: "bg-amber-500/15 text-amber-300",
  },
  danger: {
    container: "bg-red-950/30 ring-1 ring-red-500/40",
    value: "text-red-100",
    badge: "bg-red-500/15 text-red-300",
  },
}

/**
 * 위치 전용 칸. X 와 Y 를 <b>각각 다른 요소</b>로 그리고 잘라내지 않는다.
 *
 * 다른 값 칸은 `truncate`(= overflow-hidden + text-overflow: ellipsis)를 쓰는데, 좌표에 그걸
 * 적용하면 칸이 좁을 때 "X 1.20 · Y…" 처럼 Y 가 사라진다. 좌표는 두 축이 함께 있어야 뜻이 있는
 * 값이라 한쪽만 남는 표시는 없느니만 못하다.
 *
 * 그래서 여기서는 각 축을 `whitespace-nowrap` 으로 묶고 `flex-wrap` 으로 흘린다 — 칸이 넓으면
 * 한 줄에, 좁으면 X/Y 가 두 줄로 나뉘어 <b>어느 쪽도 잘리지 않는다.</b>
 */
function PositionField({
  xText,
  yText,
  tone = "default",
  title,
}: {
  xText: string
  yText: string
  tone?: FieldTone
  title?: string
}) {
  const style = TONE_STYLE[tone]
  return (
    <div className={cn("min-w-0 rounded-md px-2 py-1", style.container)} title={title}>
      <dt className="text-[10px] tracking-wide text-slate-400 uppercase">위치 (m)</dt>
      <dd
        className={cn("flex flex-wrap items-baseline gap-x-2 font-mono", style.value)}
        data-testid="vehicle-position"
      >
        <span className="whitespace-nowrap">
          <span className="text-[10px] text-slate-400">X</span> {xText}
        </span>
        <span className="whitespace-nowrap">
          <span className="text-[10px] text-slate-400">Y</span> {yText}
        </span>
      </dd>
    </div>
  )
}

function DetailField({
  label,
  value,
  mono,
  className,
  tone = "default",
  suffix,
  badge,
  title,
  testId,
  level,
}: {
  label: string
  value: string
  mono?: boolean
  className?: string
  /** 값이 낡았을 때의 강조. 기본은 강조 없음 */
  tone?: FieldTone
  /** 값 뒤에 붙는 보조 문구(예: 경과 시간) */
  suffix?: string
  /** 값 아래 배지 문구(예: 수신 지연) */
  badge?: string | null
  title?: string
  testId?: string
  /** 테스트·디버깅에서 신선도 단계를 그대로 읽을 수 있게 남긴다 */
  level?: LocationFreshnessLevel
}) {
  const style = TONE_STYLE[tone]
  return (
    <div
      className={cn("rounded-md px-2 py-1", style.container, className)}
      title={title}
      data-testid={testId}
      data-level={level}
    >
      <dt className="text-[10px] tracking-wide text-slate-400 uppercase">{label}</dt>
      <dd className={cn("truncate", style.value, mono && "font-mono")}>
        {value}
        {suffix ? <span className="ml-1 text-[10px] text-slate-400">· {suffix}</span> : null}
      </dd>
      {badge ? (
        <dd
          className={cn(
            "mt-0.5 inline-flex items-center rounded px-1 py-px text-[10px] font-medium",
            style.badge || "bg-white/5 text-slate-400",
          )}
        >
          {badge}
        </dd>
      ) : null}
    </div>
  )
}

export default VehicleDetailPanel
