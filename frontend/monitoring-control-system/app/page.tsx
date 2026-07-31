"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { AlertTriangle, Loader2, RefreshCw } from "lucide-react"
import { MainRealtimeMonitoringView } from "@/components/monitoring/MainRealtimeMonitoringView"
import { MiniMap } from "@/components/monitoring/MiniMap"
import { VehicleDetailPanel } from "@/components/monitoring/VehicleDetailPanel"
import { GlobalEmergencyStopBar } from "@/components/monitoring/GlobalEmergencyStopBar"
import { CommandNotice, type CommandNoticeState } from "@/components/monitoring/CommandNotice"
import { LoadSafetyOverlay } from "@/components/monitoring/LoadSafetyOverlay"
import { useMonitoringDashboard } from "@/hooks/useMonitoringDashboard"
import { useMonitoringSocket } from "@/hooks/useMonitoringSocket"
import { emergencyStopAll, emergencyStopVehicle } from "@/lib/api/commandApi"
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
    selectedLoadSafety,
    loadSafetyByVehicleId,
    applyLoadSafetyEvent,
  } = useMonitoringDashboard()

  const [streamStatus, setStreamStatus] = useState<StreamConnectionStatus>("idle")

  // --- FR-503 비상정지 상태 ---
  // 차량별 진행 중 명령(중복 클릭 방지). 차량 A 요청 중에도 차량 B 는 독립적으로 사용 가능하다.
  const [pendingCommandByVehicleId, setPendingCommandByVehicleId] = useState<
    Record<string, "EMERGENCY_STOP" | null>
  >({})
  const [globalEmergencyStopPending, setGlobalEmergencyStopPending] = useState(false)
  const [notice, setNotice] = useState<CommandNoticeState | null>(null)

  // unmount 후 setState 방어
  const mountedRef = useRef(true)
  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])
  const safeSet = useCallback(<T,>(setter: (v: T) => void, value: T) => {
    if (mountedRef.current) setter(value)
  }, [])

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
    onLoadSafetyEvent: applyLoadSafetyEvent,
    onConnected: handleSocketConnected,
  })

  const realtimeStatus: RealtimeConnectionStatus = wsConnected
    ? "connected"
    : wsErrored
      ? "error"
      : wsConnecting
        ? "connecting"
        : "disconnected"

  // 전체 비상정지 대상 수. dashboard 는 활성 차량만 반환하지만, active 플래그로 한 번 더 거른다.
  const activeVehicleCount = useMemo(() => vehicles.filter((v) => v.active).length, [vehicles])

  /**
   * 개별 비상정지.
   *
   * 성공 판정은 **HTTP status 가 아니라 응답 본문의 data.status** 로 한다 —
   * 발행 실패(PUBLISH_FAILED)도 HTTP 201 로 내려오기 때문이다.
   * 또한 발행 성공을 근거로 차량 상태를 ESTOP 으로 바꾸지 않는다(optimistic update 금지).
   * 실제 ESTOP 은 /topic/vehicles/status 이벤트가 도착해야만 반영된다.
   */
  const handleEmergencyStopVehicle = useCallback(
    async (vehicleId: string) => {
      if (pendingCommandByVehicleId[vehicleId]) return // 중복 클릭 차단
      if (!window.confirm(`${vehicleId} 차량에 비상정지 명령을 전송하시겠습니까?`)) return

      setPendingCommandByVehicleId((prev) => ({ ...prev, [vehicleId]: "EMERGENCY_STOP" }))
      setNotice(null)
      try {
        // reason 입력 UI 가 없으므로 임의의 기본 reason 을 만들지 않고 body 없이 보낸다.
        const response = await emergencyStopVehicle(vehicleId)

        if (response.status === "PUBLISHED") {
          safeSet(setNotice, {
            tone: "success",
            message: `${vehicleId} 비상정지 명령 전송 완료`,
            detail: `commandId ${response.commandId} · 실제 정지 여부는 차량 상태 이벤트로 확인됩니다.`,
          })
        } else if (response.status === "PUBLISH_FAILED") {
          safeSet(setNotice, {
            tone: "error",
            message: `${vehicleId} 비상정지 명령 발행 실패`,
            detail:
              response.resultMessage ??
              "MQTT 브로커로 명령을 발행하지 못했습니다. 차량은 정지되지 않았습니다.",
          })
        } else {
          safeSet(setNotice, {
            tone: "warning",
            message: `${vehicleId} 비상정지 명령 상태를 확인할 수 없습니다`,
            detail: `응답 status=${response.status ?? "null"}`,
          })
        }
      } catch (error) {
        safeSet(setNotice, {
          tone: "error",
          message: `${vehicleId} 비상정지 요청 실패`,
          detail: error instanceof Error ? error.message : "알 수 없는 오류가 발생했습니다.",
        })
      } finally {
        if (mountedRef.current) {
          setPendingCommandByVehicleId((prev) => ({ ...prev, [vehicleId]: null }))
        }
      }
    },
    [pendingCommandByVehicleId, safeSet],
  )

  /**
   * 전체 비상정지.
   *
   * 부분 실패(일부 차량만 발행 성공)를 전체 성공으로 표시하지 않는다.
   * 대상 수의 실제 응답 필드명은 totalCount 가 아니라 requestedCount 다.
   */
  const handleGlobalEmergencyStop = useCallback(async () => {
    if (globalEmergencyStopPending) return
    if (activeVehicleCount === 0) return
    if (
      !window.confirm(
        `활성 차량 ${activeVehicleCount}대에 비상정지 명령을 전송합니다. 계속하시겠습니까?`,
      )
    ) {
      return
    }

    setGlobalEmergencyStopPending(true)
    setNotice(null)
    try {
      const summary = await emergencyStopAll()
      const { requestedCount, publishedCount, failedCount, results } = summary
      const failedIds = (results ?? [])
        .filter((r) => r.status !== "PUBLISHED")
        .map((r) => r.vehicleId)

      if (requestedCount === 0) {
        safeSet(setNotice, { tone: "warning", message: "대상 활성 차량이 없습니다." })
      } else if (failedCount === 0) {
        safeSet(setNotice, {
          tone: "success",
          message: `전체 비상정지 명령 전송 완료 (${publishedCount}/${requestedCount}대)`,
          detail: "실제 정지 여부는 각 차량 상태 이벤트로 확인됩니다.",
        })
      } else if (publishedCount === 0) {
        safeSet(setNotice, {
          tone: "error",
          message: `전체 비상정지 명령 발행 실패 (${failedCount}/${requestedCount}대)`,
          detail: `실패 차량: ${failedIds.join(", ")}`,
        })
      } else {
        safeSet(setNotice, {
          tone: "warning",
          message: `${publishedCount}대 전송 성공, ${failedCount}대 발행 실패`,
          detail: `실패 차량: ${failedIds.join(", ")}`,
        })
      }
    } catch (error) {
      safeSet(setNotice, {
        tone: "error",
        message: "전체 비상정지 요청 실패",
        detail: error instanceof Error ? error.message : "알 수 없는 오류가 발생했습니다.",
      })
    } finally {
      if (mountedRef.current) setGlobalEmergencyStopPending(false)
    }
  }, [activeVehicleCount, globalEmergencyStopPending, safeSet])

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

        {/*
          개발 전용 스트림 상태 컨트롤.

          운영 빌드에서는 DOM 에 포함되지 않는다(prompt67.md 7장) — Next.js 가 NODE_ENV 를 빌드 시점에
          인라인하므로 production 빌드에서 이 분기는 통째로 제거된다. 운영 화면에서 조작자가 영상 상태를
          connected/error 로 임의 변경할 수 있으면, 실제 스트림이 없는데도 "연결됨"으로 보이게 되어
          가짜 상태를 만든다. 개발 중 각 상태의 렌더링을 확인하는 용도로만 남긴다.
        */}
        {process.env.NODE_ENV === "development" ? (
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
        ) : null}
      </header>

      {/* 초기 조회 실패 배너. 마지막으로 받은 차량 데이터는 지우지 않는다. */}
      {loadState === "error" ? (
        <DashboardErrorBanner message={errorMessage} onRetry={() => void loadDashboard()} />
      ) : null}

      {/* 안전 명령 결과 알림 (PUBLISHED / PUBLISH_FAILED / 부분 실패 구분) */}
      <CommandNotice notice={notice} onDismiss={() => setNotice(null)} />

      {/* 전체 비상정지 바 (FR-503 — 실제 API 연결) */}
      <GlobalEmergencyStopBar
        onTriggerAll={() => void handleGlobalEmergencyStop()}
        activeVehicleCount={activeVehicleCount}
        pending={globalEmergencyStopPending}
      />

      {/* 관제 본문: 좌측 영상 / 우측 상세+미니맵 */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-[2fr_1fr]">
        {/* 좌측: 디지털 트윈 영상 전용 영역 */}
        <div className="flex min-h-0 flex-col">
          <MainRealtimeMonitoringView
            streamStatus={streamStatus}
            onRetryConnection={() => setStreamStatus("connecting")}
            selectedVehicle={selectedSummary}
            realtimeStatus={realtimeStatus}
            // 적재 위험 경고는 WARNING/DANGER 일 때만 스스로 렌더된다(정상이면 null).
            overlay={
              <LoadSafetyOverlay
                loadSafety={selectedLoadSafety}
                vehicleLabel={selectedVehicle?.name ?? selectedVehicleId}
              />
            }
          />
        </div>

        {/* 우측: 상세 패널(위) + 미니맵(아래) */}
        <div className="grid min-h-0 grid-rows-2 gap-3">
          {loadState === "loading" && vehicles.length === 0 ? (
            <PanelSkeleton label="차량 정보를 불러오는 중…" />
          ) : (
            <VehicleDetailPanel
              vehicle={selectedVehicle}
              // STOP 은 아직 프론트 연결 계약이 없어 패널 내부에서 비활성으로 표시된다.
              // 여기서 Mock 핸들러를 넘기지 않는다 — 호출되지 않는 핸들러는 오해만 남긴다.
              onEmergencyStop={(id) => void handleEmergencyStopVehicle(id)}
              emergencyStopPending={
                selectedVehicleId ? pendingCommandByVehicleId[selectedVehicleId] != null : false
              }
              loadSafety={selectedLoadSafety}
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
              // 선택하지 않은 차량의 적재 위험도 마커로 인지할 수 있게 한다.
              loadSafetyByVehicleId={loadSafetyByVehicleId}
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
