"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { AlertTriangle, Loader2, RefreshCw } from "lucide-react"
import { MainRealtimeMonitoringView } from "@/components/monitoring/MainRealtimeMonitoringView"
import { MiniMap } from "@/components/monitoring/MiniMap"
import { VehicleDetailPanel } from "@/components/monitoring/VehicleDetailPanel"
import { GlobalEmergencyStopBar } from "@/components/monitoring/GlobalEmergencyStopBar"
import { useMonitoringDashboard } from "@/hooks/useMonitoringDashboard"
import { useMonitoringSocket } from "@/hooks/useMonitoringSocket"
import type {
  RealtimeConnectionStatus,
  SelectedVehicleSummary,
  StreamConnectionStatus,
} from "@/types/monitoring"

const STREAM_OPTIONS: { value: StreamConnectionStatus; label: string }[] = [
  { value: "idle", label: "미연결" },
  { value: "connecting", label: "연결 중" },
  { value: "connected", label: "연결됨" },
  { value: "error", label: "연결 실패" },
]

export default function MonitoringPage() {
  const {
    vehicles,
    selectedVehicleId,
    selectedVehicle,
    setSelectedVehicleId,
    loadState,
    errorMessage,
    loadDashboard,
    applyStatusEvent,
    applyLocationEvent,
  } = useMonitoringDashboard()

  const [streamStatus, setStreamStatus] = useState<StreamConnectionStatus>("idle")

  // 최초 진입 시 REST 조회
  useEffect(() => {
    void loadDashboard()
  }, [loadDashboard])

  /**
   * 재연결 동기화 정책 (prompt58 §21)
   *
   * SimpleBroker 는 연결이 끊긴 동안의 메시지를 버퍼링하지 않으므로, 재연결 시점에는
   * 놓친 상태/위치가 생긴다. 그래서 STOMP 연결이 성립할 때마다 dashboard 를 다시 부른다.
   *
   * 다만 "최초 mount REST 조회"와 "최초 onConnect 재조회"가 겹쳐 같은 요청이 두 번 나가는
   * 것은 낭비다. 그래서 <b>첫 onConnect 는 건너뛰고 두 번째 연결(=재연결)부터 재조회</b>한다.
   * (useMonitoringDashboard.loadDashboard 는 이전 요청을 abort 하므로 중복이 나도 안전하지만,
   *  불필요한 왕복 자체를 없애는 편이 낫다.)
   */
  const connectCountRef = useRef(0)
  const handleSocketConnected = useCallback(async () => {
    connectCountRef.current += 1
    if (connectCountRef.current === 1) {
      return // 최초 연결: mount 시 REST 조회로 이미 최신 상태다
    }
    await loadDashboard()
  }, [loadDashboard])

  const {
    connected: wsConnected,
    connecting: wsConnecting,
    errored: wsErrored,
  } = useMonitoringSocket({
    // 초기 조회가 실패한 상태에서는 굳이 실시간 연결을 유지하지 않는다(재시도 시 함께 살아난다).
    enabled: loadState !== "error",
    onStatusEvent: applyStatusEvent,
    onLocationEvent: applyLocationEvent,
    onConnected: handleSocketConnected,
  })

  const realtimeStatus: RealtimeConnectionStatus = wsConnected
    ? "connected"
    : wsErrored
      ? "error"
      : wsConnecting
        ? "connecting"
        : "disconnected"

  const selectedSummary: SelectedVehicleSummary | null = useMemo(() => {
    if (!selectedVehicle) return null
    return {
      vehicleId: selectedVehicle.vehicleId,
      name: selectedVehicle.name,
      source: selectedVehicle.source,
      status: selectedVehicle.status,
      currentTask: selectedVehicle.currentTask?.taskId ?? null,
    }
  }, [selectedVehicle])

  return (
    <main className="flex min-h-svh w-full flex-col gap-3 bg-slate-950 p-3 text-slate-100">
      <header className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-base font-semibold text-balance">디지털 트윈 실시간 관제</h1>
          <p className="text-xs text-slate-400">
            좌측 영상 · 우측 상세/미니맵 · 차량 상태·위치 실시간 연동(FR-402-1)
          </p>
        </div>

        {/* 개발 전용 스트림 상태 컨트롤 (실제 관제 화면에는 포함되지 않음) */}
        <label className="flex items-center gap-2 text-xs text-slate-400">
          <span className="hidden sm:inline">dev · 영상 상태</span>
          <select
            value={streamStatus}
            onChange={(e) => setStreamStatus(e.target.value as StreamConnectionStatus)}
            className="rounded-md border border-slate-700 bg-slate-900 px-2 py-1 text-xs text-slate-100 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
          >
            {STREAM_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </label>
      </header>

      {/* 초기 조회 실패 배너. 마지막으로 받은 차량 데이터는 지우지 않는다. */}
      {loadState === "error" ? (
        <DashboardErrorBanner message={errorMessage} onRetry={() => void loadDashboard()} />
      ) : null}

      {/* 전체 비상정지 바 (FR-402-1 범위 밖 — 실제 API 미연결) */}
      <GlobalEmergencyStopBar onTriggerAll={() => console.log("[monitoring] global emergency stop")} />

      {/* 관제 본문: 좌측 영상 / 우측 상세+미니맵 */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-[2fr_1fr]">
        {/* 좌측: 디지털 트윈 영상 전용 영역 */}
        <div className="flex min-h-0 flex-col">
          <MainRealtimeMonitoringView
            streamStatus={streamStatus}
            onRetryConnection={() => setStreamStatus("connecting")}
            selectedVehicle={selectedSummary}
            realtimeStatus={realtimeStatus}
          />
        </div>

        {/* 우측: 상세 패널(위) + 미니맵(아래) */}
        <div className="grid min-h-0 grid-rows-2 gap-3">
          {loadState === "loading" && vehicles.length === 0 ? (
            <PanelSkeleton label="차량 정보를 불러오는 중…" />
          ) : (
            <VehicleDetailPanel
              vehicle={selectedVehicle}
              onStop={(id) => console.log("[monitoring] stop", id)}
              onEmergencyStop={(id) => console.log("[monitoring] emergency stop", id)}
            />
          )}

          {loadState === "loading" && vehicles.length === 0 ? (
            <PanelSkeleton label="미니맵을 불러오는 중…" />
          ) : vehicles.length === 0 ? (
            <EmptyVehiclesPanel onRetry={() => void loadDashboard()} />
          ) : (
            <MiniMap
              vehicles={vehicles}
              selectedVehicleId={selectedVehicleId}
              onSelectVehicle={setSelectedVehicleId}
            />
          )}
        </div>
      </div>
    </main>
  )
}

function DashboardErrorBanner({
  message,
  onRetry,
}: {
  message: string | null
  onRetry: () => void
}) {
  return (
    <div
      className="flex items-center justify-between gap-3 rounded-lg border border-red-500/30 bg-red-950/40 px-3 py-2"
      role="alert"
    >
      <div className="flex min-w-0 items-center gap-2">
        <AlertTriangle className="size-4 shrink-0 text-red-400" aria-hidden="true" />
        <p className="truncate text-xs text-red-100">
          {message ?? "대시보드를 불러오지 못했습니다."}
        </p>
      </div>
      <button
        type="button"
        onClick={onRetry}
        className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-slate-100 px-2.5 py-1.5 text-xs font-medium text-slate-900 transition-colors hover:bg-white focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
      >
        <RefreshCw className="size-3.5" aria-hidden="true" />
        다시 시도
      </button>
    </div>
  )
}

function PanelSkeleton({ label }: { label: string }) {
  return (
    <section
      className="flex min-h-0 flex-col items-center justify-center gap-2 rounded-lg border border-slate-700 bg-[#0b1220]"
      aria-busy="true"
      aria-live="polite"
    >
      <Loader2 className="size-6 animate-spin text-sky-400" aria-hidden="true" />
      <p className="text-xs text-slate-400">{label}</p>
    </section>
  )
}

function EmptyVehiclesPanel({ onRetry }: { onRetry: () => void }) {
  return (
    <section className="flex min-h-0 flex-col items-center justify-center gap-3 rounded-lg border border-slate-700 bg-[#0b1220] p-6 text-center">
      <p className="text-xs text-slate-400">등록된 활성 차량이 없습니다.</p>
      <button
        type="button"
        onClick={onRetry}
        className="inline-flex items-center gap-1.5 rounded-md border border-slate-600 bg-slate-800 px-2.5 py-1.5 text-xs font-medium text-slate-100 transition-colors hover:bg-slate-700 focus-visible:ring-2 focus-visible:ring-sky-400 focus-visible:outline-none"
      >
        <RefreshCw className="size-3.5" aria-hidden="true" />
        새로고침
      </button>
    </section>
  )
}
