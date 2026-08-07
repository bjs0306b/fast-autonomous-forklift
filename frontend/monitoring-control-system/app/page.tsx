"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { AlertTriangle, RefreshCw } from "lucide-react"
import { AiMeasurementVideo, type AiVideoConnectionStatus } from "@/components/monitoring/AiMeasurementVideo"
import { MainRealtimeMonitoringView } from "@/components/monitoring/MainRealtimeMonitoringView"
import { MonitoringSlider } from "@/components/monitoring/MonitoringSlider"
import { MiniMap } from "@/components/monitoring/MiniMap"
import { VehicleDetailPanel } from "@/components/monitoring/VehicleDetailPanel"
import { GlobalEmergencyStopBar } from "@/components/monitoring/GlobalEmergencyStopBar"
import { CommandNotice, type CommandNoticeState } from "@/components/monitoring/CommandNotice"
import { useMonitoringDashboard } from "@/hooks/useMonitoringDashboard"
import { useMonitoringSocket } from "@/hooks/useMonitoringSocket"
import { emergencyStopAll, emergencyStopVehicle, stopVehicle } from "@/lib/api/commandApi"
import { AI_MEASUREMENT_STREAM_URL } from "@/lib/config/aiMeasurement"
import { useIsaacSimStream } from "@/components/monitoring/IsaacSimStream"
import type { RealtimeConnectionStatus, SelectedVehicleSummary } from "@/types/monitoring"
import type { StationMeasurementData } from "@/types/websocket"

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
    applyTaskEvent,
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
  const [activeSlide, setActiveSlide] = useState(0)
  const [aiVideoStatus, setAiVideoStatus] = useState<AiVideoConnectionStatus>(
    AI_MEASUREMENT_STREAM_URL ? "CONNECTING" : "DISCONNECTED",
  )
  const [aiVideoRetryKey, setAiVideoRetryKey] = useState(0)
  const [lastAiFrameReceivedAt, setLastAiFrameReceivedAt] = useState<string | null>(null)
  /**
   * 최근 측정 결과. 검출 상자를 영상 위에 그리는 데 쓴다.
   *
   * 측정은 트리거 시점에 한 번만 오므로 다음 측정까지 그대로 남는다 — 상자가 화면에
   * 계속 떠 있는 것은 의도한 동작이다(마지막으로 잰 결과를 보여준다).
   */
  const [lastMeasurement, setLastMeasurement] = useState<StationMeasurementData | null>(null)

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
    onTaskEvent: applyTaskEvent,
    onMeasurementEvent: setLastMeasurement,
    onConnected: handleSocketConnected,
  })

  const realtimeStatus: RealtimeConnectionStatus = wsConnected
    ? "connected"
    : wsErrored
      ? "error"
      : wsConnecting
        ? "connecting"
        : "disconnected"

  /**
   * 전체 비상정지 대상 수.
   *
   * dashboard 는 DB active=true 차량을 전부 내려주는데, 여기엔 한 번도 MQTT 로 좌표를 받은 적
   * 없는 차량(FORKLIFT-01/02, REAL-F01 같은 미연동 더미·폐기 등록)도 섞여 있다. `location`이
   * null이라는 뜻은 MonitoringService 가 "좌표를 한 번도 받은 적이 없는 차량"으로 명시한 값이라
   * (백엔드 MonitoringService.toLocationView 주석 참고), active 플래그만으로는 실제 MQTT 로
   * 연동된 차량 수를 알 수 없다. 그래서 좌표 수신 이력까지 함께 걸러야 "대상 N대"가 실제로 명령을
   * 받을 수 있는 차량 수와 일치한다.
   */
  const activeVehicleCount = useMemo(
    () => vehicles.filter((v) => v.active && v.location !== null).length,
    [vehicles],
  )

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
      {activeSlide === 0 ? (
        <>
          <header className="flex shrink-0 items-center justify-between gap-3">
            <div>
              <h1 className="text-base font-semibold text-balance">디지털 트윈 통합 관제</h1>
              <p className="text-xs text-slate-400">Isaac Sim · AI 측정 영상 · 차량 상태/위치 실시간 연동</p>
            </div>
          </header>

          {loadState === "error" ? (
            <DashboardErrorBanner message={errorMessage} onRetry={() => void loadDashboard()} />
          ) : null}
          <CommandNotice notice={notice} onDismiss={() => setNotice(null)} />
          <GlobalEmergencyStopBar
            onTriggerAll={() => void handleGlobalEmergencyStop()}
            activeVehicleCount={activeVehicleCount}
            pending={globalEmergencyStopPending}
          />
        </>
      ) : null}

      <MonitoringSlider activeIndex={activeSlide} onChange={setActiveSlide}>
        {/* 2컬럼이 되면서 lg 부터 한 화면에 들어간다 — 세로 스크롤은 그보다 좁을 때만 남긴다. */}
        <div className="h-full w-1/2 shrink-0 overflow-y-auto pb-5 lg:overflow-hidden lg:pb-1" aria-label="디지털 트윈 통합 관제 화면">
          {/* AI 측정 영상 전용 컬럼은 없앴다 — Isaac Sim 영상 안쪽 오른쪽 위 PIP 로 들어간다.
              남는 축은 "중심 영상 : 차량 관제" 둘뿐이다. */}
          <div className="grid min-h-full min-w-0 grid-cols-1 gap-2 lg:h-full lg:min-h-0 lg:grid-cols-[minmax(0,1fr)_minmax(360px,0.42fr)] 2xl:grid-cols-[minmax(0,1fr)_minmax(380px,0.34fr)]">
            <div className="flex min-h-[480px] min-w-0 flex-col lg:min-h-0">
              <MainRealtimeMonitoringView
                streamStatus={streamStatus}
                streamError={streamError}
                streamContainerRef={streamContainerRef}
                streamVideoRef={streamVideoRef}
                onRetryConnection={reconnectStream}
                selectedVehicle={selectedSummary}
                realtimeStatus={realtimeStatus}
                pip={
                  <AiMeasurementVideo
                    streamUrl={AI_MEASUREMENT_STREAM_URL}
                    connectionStatus={aiVideoStatus}
                    active={activeSlide === 0}
                    pip
                    retryKey={aiVideoRetryKey}
                    boxes={lastMeasurement?.boxes}
                    frameWidth={lastMeasurement?.frameWidth}
                    frameHeight={lastMeasurement?.frameHeight}
                    onConnectionStatusChange={setAiVideoStatus}
                    onFrameLoaded={setLastAiFrameReceivedAt}
                    onOpenFullscreen={() => setActiveSlide(1)}
                    onRetry={() => setAiVideoRetryKey((value) => value + 1)}
                  />
                }
              />
            </div>

            <VehicleControlPanel
              vehicles={vehicles}
              selectedVehicleId={selectedVehicleId}
              selectedVehicle={selectedVehicle}
              setSelectedVehicleId={setSelectedVehicleId}
              realtimeStatus={realtimeStatus}
              loadState={loadState}
              loadDashboard={loadDashboard}
              handleStopVehicle={handleStopVehicle}
              handleEmergencyStopVehicle={handleEmergencyStopVehicle}
              pendingCommandByVehicleId={pendingCommandByVehicleId}
            />
          </div>
        </div>

        <div className="h-full w-1/2 shrink-0 overflow-hidden px-0.5 pb-5" aria-label="AI 측정 영상 전체 보기 화면">
          <AiMeasurementVideo
            streamUrl={AI_MEASUREMENT_STREAM_URL}
            connectionStatus={aiVideoStatus}
            active={activeSlide === 1}
            fullscreen
            retryKey={aiVideoRetryKey}
            lastFrameReceivedAt={lastAiFrameReceivedAt}
            boxes={lastMeasurement?.boxes}
            frameWidth={lastMeasurement?.frameWidth}
            frameHeight={lastMeasurement?.frameHeight}
            onConnectionStatusChange={setAiVideoStatus}
            onFrameLoaded={setLastAiFrameReceivedAt}
            onCloseFullscreen={() => setActiveSlide(0)}
            onRetry={() => setAiVideoRetryKey((value) => value + 1)}
            className="h-full"
          />
        </div>
      </MonitoringSlider>
    </main>
  )
}

function VehicleControlPanel({
  vehicles,
  selectedVehicleId,
  selectedVehicle,
  setSelectedVehicleId,
  realtimeStatus,
  loadState,
  loadDashboard,
  handleStopVehicle,
  handleEmergencyStopVehicle,
  pendingCommandByVehicleId,
}: {
  vehicles: ReturnType<typeof useMonitoringDashboard>["vehicles"]
  selectedVehicleId: string | null
  selectedVehicle: ReturnType<typeof useMonitoringDashboard>["selectedVehicle"]
  setSelectedVehicleId: (id: string | null) => void
  realtimeStatus: RealtimeConnectionStatus
  loadState: ReturnType<typeof useMonitoringDashboard>["loadState"]
  loadDashboard: () => Promise<void>
  handleStopVehicle: (id: string) => Promise<void>
  handleEmergencyStopVehicle: (id: string) => Promise<void>
  pendingCommandByVehicleId: Record<string, "STOP" | "EMERGENCY_STOP" | null>
}) {
  return (
    // 컬럼이 하나 줄어 세로 여유가 생겼다 — 고정 min-h 를 낮춰 패널 자체가 화면 높이를
    // 넘기지 않게 한다(넘기면 우측 패널에 불필요한 세로 스크롤이 생긴다).
    <section className="flex min-h-[520px] min-w-0 flex-col overflow-hidden rounded-lg border border-slate-700 bg-[#0b1220] lg:h-full lg:min-h-0" aria-label="차량 관제" aria-busy={loadState === "loading"}>
      <header className="flex shrink-0 items-center border-b border-slate-700 px-3 py-1.5">
        <h2 className="text-sm font-semibold text-slate-100">차량 관제</h2>
      </header>
      <div className="h-[clamp(240px,30dvh,340px)] min-h-0 shrink-0 overflow-hidden">
        <MiniMap vehicles={vehicles} selectedVehicleId={selectedVehicleId} onSelectVehicle={setSelectedVehicleId} onClearSelection={() => setSelectedVehicleId(null)} realtimeStatus={realtimeStatus} onRefresh={() => void loadDashboard()} className="h-full min-h-0 min-w-0 overflow-hidden rounded-none border-0 bg-transparent" />
      </div>
      <div className="min-h-0 flex-1 overflow-hidden border-t border-slate-700">
        <VehicleDetailPanel
          vehicle={selectedVehicle}
          onStop={(id) => void handleStopVehicle(id)}
          onEmergencyStop={(id) => void handleEmergencyStopVehicle(id)}
          stopPending={selectedVehicleId ? pendingCommandByVehicleId[selectedVehicleId] === "STOP" : false}
          emergencyStopPending={selectedVehicleId ? pendingCommandByVehicleId[selectedVehicleId] === "EMERGENCY_STOP" : false}
          className="h-full min-h-0 rounded-none border-0 bg-transparent"
        />
      </div>
    </section>
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
