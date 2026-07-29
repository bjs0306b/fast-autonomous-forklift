"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { fetchMonitoringDashboard } from "@/lib/api/monitoringApi"
import { fetchAllLatestLoadSafety } from "@/lib/api/loadSafetyApi"
import { normalizeLocationEvent, normalizeStatusEvent } from "@/lib/realtimeEvent"
import { normalizeLoadSafetyEvent } from "@/lib/loadSafety"
import { isAbortError } from "@/types/api"
import type {
  DashboardResponse,
  DashboardTask,
  DashboardVehicle,
} from "@/types/monitoring"
import type { LoadSafetyState } from "@/types/loadSafety"
import type { RealtimeEvent } from "@/types/websocket"

export type DashboardLoadState = "loading" | "loaded" | "error"

interface MonitoringState {
  vehiclesById: Record<string, DashboardVehicle>
  vehicleOrder: string[]
  tasksById: Record<string, DashboardTask>
}

const EMPTY_STATE: MonitoringState = {
  vehiclesById: {},
  vehicleOrder: [],
  tasksById: {},
}

/**
 * 적재 안전 상태는 차량 목록과 <b>수명이 다르므로</b> 별도 state 로 둔다.
 *
 * dashboard 응답에는 적재 안전 필드가 없다(백엔드 계약을 바꾸지 않았다). 이 값은 전용 REST 로 한 번
 * 채우고 이후 WebSocket 으로 갱신된다. vehiclesById 안에 병합하면 dashboard 재조회(재연결 시)마다
 * 통째로 교체되면서 방금 받은 실시간 적재 안전 값이 사라진다.
 */
type LoadSafetyMap = Record<string, LoadSafetyState>

/**
 * dashboard 응답을 vehicleId 기준으로 정규화한다.
 * vehicleOrder 는 백엔드가 준 순서(vehicleId 오름차순)를 그대로 보존한다.
 */
function toMonitoringState(dashboard: DashboardResponse): MonitoringState {
  const vehiclesById: Record<string, DashboardVehicle> = {}
  const vehicleOrder: string[] = []
  for (const vehicle of dashboard.vehicles) {
    if (!vehicle || typeof vehicle.vehicleId !== "string") continue
    if (!vehiclesById[vehicle.vehicleId]) {
      vehicleOrder.push(vehicle.vehicleId)
    }
    vehiclesById[vehicle.vehicleId] = vehicle
  }

  const tasksById: Record<string, DashboardTask> = {}
  for (const task of dashboard.tasks) {
    if (!task || typeof task.taskId !== "string") continue
    tasksById[task.taskId] = task
  }

  return { vehiclesById, vehicleOrder, tasksById }
}

/**
 * 새 차량 목록에 맞춰 선택 차량을 결정한다.
 * - 기존 선택이 새 목록에 남아 있으면 유지 (재연결 후에도 선택이 튀지 않게)
 * - 없으면 위치가 있는 첫 차량
 * - 그것도 없으면 첫 차량
 * - 차량이 하나도 없으면 null
 */
function resolveSelectedVehicleId(
  previousSelectedId: string | null,
  state: MonitoringState,
): string | null {
  if (previousSelectedId && state.vehiclesById[previousSelectedId]) {
    return previousSelectedId
  }
  const firstWithLocation = state.vehicleOrder.find((id) => {
    const location = state.vehiclesById[id]?.location
    return location != null && location.x != null && location.y != null
  })
  return firstWithLocation ?? state.vehicleOrder[0] ?? null
}

/**
 * 관제 화면 상태 저장소.
 *
 * 실시간 이벤트는 vehiclesById 의 해당 차량만 불변 갱신하며,
 * selectedVehicleId 는 어떤 이벤트로도 바뀌지 않는다(순수 프론트 로컬 상태).
 */
export function useMonitoringDashboard() {
  const [state, setState] = useState<MonitoringState>(EMPTY_STATE)
  const [loadSafetyByVehicleId, setLoadSafetyByVehicleId] = useState<LoadSafetyMap>({})
  const [selectedVehicleId, setSelectedVehicleId] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<DashboardLoadState>("loading")
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  // unmount 후 setState 를 막고, 진행 중 요청을 취소하기 위한 ref
  const mountedRef = useRef(true)
  const abortRef = useRef<AbortController | null>(null)

  /**
   * 등록된 차량 id 집합. applyLoadSafetyEvent 가 "미등록 차량 무시"를 판단할 때 쓴다.
   * state 를 직접 의존하면 차량 목록이 바뀔 때마다 콜백이 새로 만들어져 STOMP 핸들러가 흔들리므로
   * ref 로 최신 값만 따라가게 한다(useMonitoringSocket 이 콜백을 ref 에 담는 것과 같은 이유).
   */
  const vehicleIdsRef = useRef<Set<string>>(new Set())
  useEffect(() => {
    vehicleIdsRef.current = new Set(state.vehicleOrder)
  }, [state.vehicleOrder])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      abortRef.current?.abort()
    }
  }, [])

  /**
   * dashboard 를 조회해 상태를 통째로 교체한다.
   * 최초 진입과 WebSocket 재연결 양쪽에서 호출되므로 useCallback 으로 안정화한다.
   */
  const loadDashboard = useCallback(async (): Promise<void> => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    if (mountedRef.current) {
      setLoadState((prev) => (prev === "loaded" ? prev : "loading"))
    }

    try {
      const dashboard = await fetchMonitoringDashboard(controller.signal)
      if (!mountedRef.current || controller.signal.aborted) return

      const nextState = toMonitoringState(dashboard)
      setState(nextState)
      setSelectedVehicleId((prev) => resolveSelectedVehicleId(prev, nextState))
      setErrorMessage(null)
      setLoadState("loaded")
    } catch (error) {
      if (isAbortError(error) || !mountedRef.current) return
      setErrorMessage(error instanceof Error ? error.message : "알 수 없는 오류가 발생했습니다.")
      setLoadState("error")
    }

    // 적재 안전 초기값은 dashboard 와 별개 엔드포인트라 따로 채운다.
    // 실패해도 대시보드 전체를 오류로 만들지 않는다 — 적재 안전은 부가 정보이고, 이 값이 없다고
    // 차량 위치·상태 관제를 막을 이유가 없다. 화면에는 "데이터 없음"으로 표시된다.
    try {
      const loadSafety = await fetchAllLatestLoadSafety(controller.signal)
      if (!mountedRef.current || controller.signal.aborted) return
      const next: LoadSafetyMap = {}
      for (const item of loadSafety) {
        if (item && typeof item.vehicleId === "string") {
          next[item.vehicleId] = item
        }
      }
      setLoadSafetyByVehicleId(next)
    } catch (error) {
      if (isAbortError(error) || !mountedRef.current) return
      console.warn("[monitoring] 적재 안전 초기 조회 실패(대시보드는 계속 표시):", error)
    }
  }, [])

  /** 차량 상태 이벤트 반영. 등록되지 않은 vehicleId 는 무시한다(임의로 차량을 추가하지 않는다). */
  const applyStatusEvent = useCallback((event: RealtimeEvent<unknown>) => {
    const update = normalizeStatusEvent(event)
    if (!update) return

    setState((prev) => {
      const current = prev.vehiclesById[update.vehicleId]
      if (!current) {
        console.warn("[monitoring] 등록되지 않은 차량의 상태 이벤트 무시:", update.vehicleId)
        return prev
      }
      return {
        ...prev,
        vehiclesById: {
          ...prev.vehiclesById,
          [update.vehicleId]: {
            ...current,
            status: update.status,
            lastUpdatedAt: update.updatedAt ?? current.lastUpdatedAt,
          },
        },
      }
    })
  }, [])

  /** 차량 위치 이벤트 반영. ROS2/Isaac 두 payload 는 normalizeLocationEvent 가 흡수한다. */
  const applyLocationEvent = useCallback((event: RealtimeEvent<unknown>) => {
    const location = normalizeLocationEvent(event)
    if (!location) return

    setState((prev) => {
      const current = prev.vehiclesById[event.vehicleId]
      if (!current) {
        console.warn("[monitoring] 등록되지 않은 차량의 위치 이벤트 무시:", event.vehicleId)
        return prev
      }
      return {
        ...prev,
        vehiclesById: {
          ...prev.vehiclesById,
          [event.vehicleId]: {
            ...current,
            location: {
              x: location.x,
              y: location.y,
              heading: location.heading,
              speed: location.speed,
              frameId: location.frameId,
              messageAt: location.messageAt,
              receivedAt: location.receivedAt,
              source: location.source,
            },
          },
        },
      }
    })
  }, [])

  /**
   * 적재 안전 이벤트 반영. 등록되지 않은 vehicleId 는 무시한다(3장 9번) —
   * 차량 상태 이벤트와 동일하게, 실시간 메시지로 차량을 새로 만들지 않는다.
   */
  const applyLoadSafetyEvent = useCallback((event: RealtimeEvent<unknown>) => {
    const next = normalizeLoadSafetyEvent(event)
    if (!next) return

    setLoadSafetyByVehicleId((prev) => {
      if (!vehicleIdsRef.current.has(next.vehicleId)) {
        console.warn("[monitoring] 등록되지 않은 차량의 적재 안전 이벤트 무시:", next.vehicleId)
        return prev
      }
      return { ...prev, [next.vehicleId]: next }
    })
  }, [])

  const vehicles = useMemo(
    () =>
      state.vehicleOrder
        .map((id) => state.vehiclesById[id])
        .filter((v): v is DashboardVehicle => Boolean(v)),
    [state.vehicleOrder, state.vehiclesById],
  )

  // selectedVehicle 은 별도 state 로 저장하지 않고 항상 파생한다.
  const selectedVehicle = selectedVehicleId
    ? (state.vehiclesById[selectedVehicleId] ?? null)
    : null

  // 선택 차량의 적재 안전 상태. 차량 선택이 바뀌면 자동으로 해당 차량 값이 나온다(3장 8번) —
  // 선택 변경 시 별도 요청을 보내지 않는다(전체 목록을 이미 받아 두고 WebSocket 으로 갱신 중이다).
  const selectedLoadSafety = selectedVehicleId
    ? (loadSafetyByVehicleId[selectedVehicleId] ?? null)
    : null

  return {
    vehicles,
    vehiclesById: state.vehiclesById,
    vehicleOrder: state.vehicleOrder,
    tasksById: state.tasksById,
    selectedVehicleId,
    selectedVehicle,
    setSelectedVehicleId,
    loadState,
    errorMessage,
    loadDashboard,
    applyStatusEvent,
    applyLocationEvent,
    loadSafetyByVehicleId,
    selectedLoadSafety,
    applyLoadSafetyEvent,
  }
}
