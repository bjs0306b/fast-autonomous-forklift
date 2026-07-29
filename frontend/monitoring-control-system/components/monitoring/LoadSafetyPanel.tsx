"use client"

import { CircleHelp, OctagonAlert, ShieldCheck, TriangleAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { formatClockTime, formatNumber } from "@/lib/format"
import { isLoadSafetyStale, loadSafetyAgeMs } from "@/lib/loadSafety"
import type { LoadSafetyRiskLevel, LoadSafetyState } from "@/types/loadSafety"
import { LOAD_SAFETY_RISK_LABEL } from "@/types/loadSafety"

/**
 * LoadSafetyPanel
 *
 * 선택 차량의 적재 화물 안전 상태를 표시한다(prompt63.md 3장 4·6·7번).
 *
 * <b>이 컴포넌트는 위험도를 계산하지 않는다.</b> roll/pitch 를 임계값과 비교하는 코드가 여기 없는 것은
 * 의도적이다 — 백엔드가 중계한 riskLevel 을 그대로 표시할 뿐이다(3장 "프론트는 위험도를 계산하지
 * 않는다"). 아래 RISK_STYLE 은 판정이 아니라 <b>이미 판정된 값의 표기 방식</b>이다.
 */

// 라벨 문구는 types/loadSafety.ts 의 LOAD_SAFETY_RISK_LABEL 하나만 쓴다 —
// 미니맵 마커의 접근성 라벨과 문구가 갈라지지 않게 하기 위해서다.
const RISK_STYLE: Record<
  LoadSafetyRiskLevel,
  { label: string; icon: typeof ShieldCheck; className: string; iconClass: string }
> = {
  NORMAL: {
    label: LOAD_SAFETY_RISK_LABEL.NORMAL,
    icon: ShieldCheck,
    className: "border-emerald-500/30 bg-emerald-950/30 text-emerald-100",
    iconClass: "text-emerald-400",
  },
  CAUTION: {
    label: LOAD_SAFETY_RISK_LABEL.CAUTION,
    icon: TriangleAlert,
    className: "border-amber-500/30 bg-amber-950/30 text-amber-100",
    iconClass: "text-amber-400",
  },
  WARNING: {
    label: LOAD_SAFETY_RISK_LABEL.WARNING,
    icon: TriangleAlert,
    className: "border-orange-500/40 bg-orange-950/40 text-orange-100",
    iconClass: "text-orange-400",
  },
  DANGER: {
    label: LOAD_SAFETY_RISK_LABEL.DANGER,
    icon: OctagonAlert,
    className: "border-red-500/50 bg-red-950/50 text-red-100",
    iconClass: "text-red-400",
  },
  UNKNOWN: {
    label: LOAD_SAFETY_RISK_LABEL.UNKNOWN,
    icon: CircleHelp,
    className: "border-slate-600/50 bg-slate-800/40 text-slate-200",
    iconClass: "text-slate-400",
  },
}

export function LoadSafetyPanel({
  loadSafety,
  className,
}: {
  /** 선택 차량의 적재 안전 상태. 한 번도 수신하지 못했으면 null */
  loadSafety: LoadSafetyState | null
  className?: string
}) {
  return (
    <section
      className={cn("flex flex-col gap-2", className)}
      aria-label="적재 화물 안전 상태"
      data-testid="load-safety-panel"
    >
      <div className="flex items-center justify-between">
        <span className="text-[10px] font-medium tracking-wide text-slate-400 uppercase">
          적재 안전
        </span>
        {loadSafety?.source ? (
          <span className="rounded bg-white/5 px-1.5 py-0.5 font-mono text-[9px] text-slate-400">
            {loadSafety.source}
          </span>
        ) : null}
      </div>

      {loadSafety ? (
        <LoadSafetyContent loadSafety={loadSafety} />
      ) : (
        <p
          className="rounded-md bg-white/5 px-2.5 py-2 text-xs text-slate-400"
          data-testid="load-safety-empty"
        >
          적재 안전 데이터 미수신 — 비전·센서에서 아직 전달된 값이 없습니다.
        </p>
      )}
    </section>
  )
}

function LoadSafetyContent({ loadSafety }: { loadSafety: LoadSafetyState }) {
  const style = RISK_STYLE[loadSafety.riskLevel] ?? RISK_STYLE.UNKNOWN
  const Icon = style.icon
  const stale = isLoadSafetyStale(loadSafety)
  const ageMs = loadSafetyAgeMs(loadSafety)

  return (
    <>
      {/* 위험 단계 배지 + 메시지 */}
      <div
        className={cn("flex items-start gap-2 rounded-md border px-2.5 py-2", style.className)}
        data-testid="load-safety-risk"
        data-risk-level={loadSafety.riskLevel}
      >
        <Icon className={cn("mt-0.5 size-4 shrink-0", style.iconClass)} aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5">
            <span className="text-xs font-semibold">{style.label}</span>
            {loadSafety.riskCode ? (
              <span className="rounded bg-black/25 px-1 py-0.5 font-mono text-[9px] opacity-80">
                {loadSafety.riskCode}
              </span>
            ) : null}
          </div>
          {loadSafety.message ? (
            <p className="mt-0.5 text-[11px] text-pretty opacity-90">{loadSafety.message}</p>
          ) : null}
        </div>
      </div>

      {/* 오래된 데이터 경고. 위험 단계를 바꾸지 않고 신선도만 덧붙인다. */}
      {stale ? (
        <p
          className="rounded-md border border-slate-600/40 bg-slate-800/40 px-2.5 py-1.5 text-[11px] text-slate-300"
          data-testid="load-safety-stale"
        >
          ⏱ 마지막 감지 이후 {ageMs != null ? Math.round(ageMs / 1000) : "?"}초 경과 — 최신 값이
          아닐 수 있습니다.
        </p>
      ) : null}

      {/* 측정값 */}
      <dl className="grid grid-cols-2 gap-2 text-xs">
        <Field label="포크 높이 (m)" value={formatOrDash(loadSafety.forkHeight, 2)} />
        <Field label="화물 높이 (m)" value={formatOrDash(loadSafety.cargoHeight, 2)} />
        <Field label="Roll (°)" value={formatOrDash(loadSafety.roll, 1)} />
        <Field label="Pitch (°)" value={formatOrDash(loadSafety.pitch, 1)} />
        <Field label="편향 X (m)" value={formatOrDash(loadSafety.loadOffsetX, 2)} />
        <Field label="편향 Y (m)" value={formatOrDash(loadSafety.loadOffsetY, 2)} />
      </dl>

      <div className="flex items-center justify-between gap-2 text-[10px] text-slate-400">
        <span className="truncate font-mono">{loadSafety.cargoId ?? "화물 미인식"}</span>
        <span className="font-mono">감지 {formatClockTime(loadSafety.detectedAt)}</span>
      </div>
    </>
  )
}

/** 값이 없으면 0 이 아니라 "—" 로 표시한다 — 미측정과 0 을 구분해야 한다. */
function formatOrDash(value: number | null, digits: number): string {
  return value != null ? formatNumber(value, digits) : "—"
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-white/5 px-2.5 py-1.5">
      <dt className="text-[10px] tracking-wide text-slate-400 uppercase">{label}</dt>
      <dd className="mt-0.5 truncate font-mono text-slate-100">{value}</dd>
    </div>
  )
}

export default LoadSafetyPanel
