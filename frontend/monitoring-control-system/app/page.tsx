"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { AlertTriangle, RefreshCw } from "lucide-react"
import { MainRealtimeMonitoringView } from "@/components/monitoring/MainRealtimeMonitoringView"
import { MiniMap } from "@/components/monitoring/MiniMap"
import { VehicleDetailPanel } from "@/components/monitoring/VehicleDetailPanel"
import { GlobalEmergencyStopBar } from "@/components/monitoring/GlobalEmergencyStopBar"
import { CommandNotice, type CommandNoticeState } from "@/components/monitoring/CommandNotice"
import { useMonitoringDashboard } from "@/hooks/useMonitoringDashboard"
import { useMonitoringSocket } from "@/hooks/useMonitoringSocket"
import { emergencyStopAll, emergencyStopVehicle, stopVehicle } from "@/lib/api/commandApi"
import { useIsaacSimStream } from "@/components/monitoring/IsaacSimStream"
import type { RealtimeConnectionStatus, SelectedVehicleSummary } from "@/types/monitoring"

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

  /**
   * Isaac Sim 영상 연결.
   *
   * 마운트 즉시 스스로 연결을 시작하고, 상태는 실제 WebRTC 이벤트로만 바뀐다(prompt80).
   * 페이지에는 이 상태를 바꾸는 UI 가 없다 — 예전의 dev 전용 select 는 제거했다.
   */
  const {
    status: streamStatus,
    error: streamError,
    containerRef: streamContainerRef,
    videoRef: streamVideoRef,
    reconnect: reconnectStream,
  } = useIsaacSimStream()

  // --- FR-503 비상정지 상태 ---
  // 차량별 진행 중 명령(중복 클릭 방지). 차량 A 요청 중에도 차량 B 는 독립적으로 사용 가능하다.
  const [pendingCommandByVehicleId, setPendingCommandByVehicleId] = useState<
    Record<string, "STOP" | "EMERGENCY_STOP" | null>
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
      // 확인 창을 두지 않는다(prompt79) — 비상정지는 한 번의 클릭으로 즉시 나가야 한다.
      // 오발행 방지는 확인 창 대신 (1) 아래 pending 가드, (2) 버튼 disabled,
      // (3) 이미 ESTOP 인 차량의 버튼 비활성(VehicleDetailPanel)으로 한다.
      if (pendingCommandByVehicleId[vehicleId]) return // 중복 클릭 차단

      setPendingCommandByVehicleId((prev) => ({ ...prev, [vehicleId]: "EMERGENCY_STOP" }))
      setNotice(null)
      try {
        // 안전 명령 API는 요청 본문 없이 호출한다.
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

  /** 선택 차량 일반 정지. 발행 성공 뒤에도 상태는 WebSocket 수신 전까지 그대로 둔다. */
  const handleStopVehicle = useCallback(
    async (vehicleId: string) => {
      if (pendingCommandByVehicleId[vehicleId]) return

      setPendingCommandByVehicleId((prev) => ({ ...prev, [vehicleId]: "STOP" }))
      setNotice(null)
      try {
        const response = await stopVehicle(vehicleId)
        if (response.status === "PUBLISHED") {
          safeSet(setNotice, {
            tone: "success",
            message: `${vehicleId} 정지 명령을 전송했습니다.`,
            detail: `commandId ${response.commandId} · 실제 상태는 차량 상태 이벤트로 갱신됩니다.`,
          })
        } else if (response.status === "PUBLISH_FAILED") {
          safeSet(setNotice, {
            tone: "error",
            message: `${vehicleId} 정지 명령 발행에 실패했습니다.`,
            detail:
              response.resultMessage ??
              "MQTT 브로커로 명령을 발행하지 못했습니다. 차량 상태를 확인해 주세요.",
          })
        } else {
          safeSet(setNotice, {
            tone: "warning",
            message: `${vehicleId} 정지 명령 상태를 확인할 수 없습니다.`,
            detail: `응답 status=${response.status ?? "null"}`,
          })
        }
      } catch (error) {
        safeSet(setNotice, {
          tone: "error",
          message: `${vehicleId} 정지 요청에 실패했습니다.`,
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
    // 확인 창을 두지 않는다(prompt79). 대상은 여전히 **현재 활성 차량 전체**이며,
    // 활성 차량이 0대면 요청 자체를 보내지 않는다(버튼도 disabled 다).
    if (globalEmergencyStopPending) return
    if (activeVehicleCount === 0) return

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
      status: selectedVehicle.status,
      currentTask: selectedVehicle.currentTask?.taskId ?? null,
    }
  }, [selectedVehicle])

  return (
    <main className="flex h-dvh w-full min-w-0 flex-col gap-[clamp(0.375rem,0.6vw,0.5rem)] overflow-hidden bg-slate-950 p-[clamp(0.5rem,0.7vw,0.75rem)] text-slate-100">
      <header className="flex shrink-0 items-center justify-between gap-3">
        <div>
          <h1 className="text-base font-semibold text-balance">디지털 트윈 실시간 관제</h1>
          <p className="text-xs text-slate-400">
            좌측 영상 · 우측 미니맵/상세 · 차량 상태·위치 실시간 연동(FR-402-1)
          </p>
        </div>

        {/*
          영상 상태를 사람이 고르는 컨트롤은 없다(prompt80).
          dev 전용 select 도 제거했다 — 개발 중이든 아니든 화면의 연결 문구는 실제 WebRTC
          상태여야 하고, 손으로 바꿀 수 있는 상태값은 결국 "가짜 연결됨"을 만든다.
        */}
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

      {/* 관제 본문: 좌측 영상 / 우측 통합 차량 관제 패널 */}
      <div className="grid min-h-0 min-w-0 flex-1 grid-cols-1 gap-[clamp(0.5rem,0.7vw,0.75rem)] overflow-y-auto lg:grid-cols-[minmax(0,2fr)_minmax(360px,1fr)] lg:overflow-hidden">
        {/* 좌측: 디지털 트윈 영상 전용 영역 */}
        <div className="flex min-h-0 min-w-0 flex-col">
          <MainRealtimeMonitoringView
            streamStatus={streamStatus}
            streamError={streamError}
            streamContainerRef={streamContainerRef}
            streamVideoRef={streamVideoRef}
            // 자동 재연결(3초)과 별개로, 기다리지 않고 즉시 다시 시도한다.
            onRetryConnection={reconnectStream}
            selectedVehicle={selectedSummary}
            realtimeStatus={realtimeStatus}
          />
        </div>

        {/* 우측: 외곽선 하나 안에서 미니맵 → 차량 상세 순서로 이어지는 통합 패널. */}
        <section
          className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220]"
          aria-label="차량 관제"
          aria-busy={loadState === "loading"}
        >
          <header className="shrink-0 border-b border-slate-700 px-3 py-1.5">
            <h2 className="text-sm font-semibold text-slate-100">차량 관제</h2>
          </header>

          {/* 데이터가 아직 없어도 지도와 빈 상세를 숨기지 않는다. */}
          <MiniMap
            vehicles={vehicles}
            selectedVehicleId={selectedVehicleId}
            onSelectVehicle={setSelectedVehicleId}
            realtimeStatus={realtimeStatus}
            onRefresh={() => void loadDashboard()}
            className="min-h-0 flex-[0.75] overflow-hidden rounded-none border-0 bg-transparent"
          />

          <div className="min-h-0 flex-1 overflow-hidden border-t border-slate-700">
            <VehicleDetailPanel
              vehicle={selectedVehicle}
              onStop={(id) => void handleStopVehicle(id)}
              onEmergencyStop={(id) => void handleEmergencyStopVehicle(id)}
              stopPending={
                selectedVehicleId ? pendingCommandByVehicleId[selectedVehicleId] === "STOP" : false
              }
              emergencyStopPending={
                selectedVehicleId
                  ? pendingCommandByVehicleId[selectedVehicleId] === "EMERGENCY_STOP"
                  : false
              }
              className="h-full min-h-0 rounded-none border-0 bg-transparent"
            />
          </div>
        </section>
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
      className="flex shrink-0 items-center justify-between gap-3 rounded-lg border border-red-500/30 bg-red-950/40 px-3 py-1.5"
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

// 차량 0대 안내는 MiniMap 안으로 옮겼다(지도를 지우지 않고 위에 얹는다).
// 별도 패널 컴포넌트는 더 이상 쓰이지 않아 제거했다 — 호출되지 않는 컴포넌트가 남아 있으면
// 다음 사람이 "이 화면도 뜨는구나"라고 오해한다.
