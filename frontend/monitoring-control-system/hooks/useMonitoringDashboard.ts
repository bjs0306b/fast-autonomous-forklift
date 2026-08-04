"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { fetchMonitoringDashboard } from "@/lib/api/monitoringApi"
import { sortVehicleIds } from "@/lib/monitoring/vehiclePriority"
import { normalizeLocationEvent, normalizeStatusEvent } from "@/lib/realtimeEvent"
import { isAbortError } from "@/types/api"
import type { DashboardResponse, DashboardTask, DashboardVehicle } from "@/types/monitoring"
import type { RealtimeEvent, TransportTaskEvent } from "@/types/websocket"
import { TRANSPORT_TASK_FAILED_EVENT_TYPE } from "@/types/websocket"

export type DashboardLoadState = "loading" | "loaded" | "error"

interface MonitoringState {
  vehiclesById: Record<string, DashboardVehicle>
  vehicleOrder: string[]
  tasksById: Record<string, DashboardTask>
}

const EMPTY_STATE: MonitoringState = { vehiclesById: {}, vehicleOrder: [], tasksById: {} }

function toMonitoringState(dashboard: DashboardResponse): MonitoringState {
  const vehiclesById: Record<string, DashboardVehicle> = {}
  const vehicleOrder: string[] = []
  for (const vehicle of dashboard.vehicles) {
    if (!vehicle || typeof vehicle.vehicleId !== "string") continue
    if (!vehiclesById[vehicle.vehicleId]) vehicleOrder.push(vehicle.vehicleId)
    vehiclesById[vehicle.vehicleId] = vehicle
  }
  const tasksById: Record<string, DashboardTask> = {}
  for (const task of dashboard.tasks) {
    if (task && typeof task.taskId === "string") tasksById[task.taskId] = task
  }
  // 주 시연 차량(SIM-F01)이 항상 맨 앞에 오도록 정렬한다. sortVehicleIds 가 복사본을 만들므로
  // API 응답 배열은 그대로 둔다.
  return { vehiclesById, vehicleOrder: sortVehicleIds(vehicleOrder), tasksById }
}

/**
 * 대시보드를 다시 불러온 뒤 어떤 차량을 선택 상태로 둘지 정한다.
 *
 * <b>차량을 자동으로 선택하지 않는다.</b> 선택은 오직 사용자가 마커를 클릭했을 때만 생긴다 —
 * 자동 선택은 "내가 고르지 않은 차량의 정보"를 상세 패널에 띄우고, 관제 화면에서 그것은 지금
 * 어느 차량을 보고 있는지 착각하게 만든다. 그래서 목록에 SIM-F01 이 있어도, 차량이 한 대뿐이어도
 * 고르지 않는다(정렬 우선순위는 목록 표시 순서일 뿐 선택과 무관하다).
 *
 * 하는 일은 두 가지뿐이다.
 *   1. 기존 선택이 목록에 남아 있으면 그대로 유지 — 갱신 때마다 선택이 풀리지 않게
 *   2. 그 차량이 목록에서 사라졌으면(삭제·비활성) null 로 되돌려 미선택 상태로
 */
function resolveSelectedVehicleId(previous: string | null, state: MonitoringState): string | null {
  if (previous && state.vehiclesById[previous]) return previous
  return null
}

function isOlder(incoming: string | null, current: string | null | undefined): boolean {
  if (!incoming || !current) return false
  const incomingTime = Date.parse(incoming)
  const currentTime = Date.parse(current)
  return !Number.isNaN(incomingTime) && !Number.isNaN(currentTime) && incomingTime < currentTime
}

export function useMonitoringDashboard() {
  const [state, setState] = useState<MonitoringState>(EMPTY_STATE)
  const [selectedVehicleId, setSelectedVehicleId] = useState<string | null>(null)
  const [loadState, setLoadState] = useState<DashboardLoadState>("loading")
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const mountedRef = useRef(true)
  const abortRef = useRef<AbortController | null>(null)

  // 이벤트 핸들러가 렌더 사이에 최신 차량 맵을 읽기 위한 거울. 상태 자체를 의존성으로 걸면
  // applyStatusEvent 가 매 갱신마다 새로 만들어져 STOMP 구독이 계속 다시 붙는다.
  const vehiclesRef = useRef<Record<string, DashboardVehicle>>({})
  useEffect(() => {
    vehiclesRef.current = state.vehiclesById
  }, [state.vehiclesById])

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      abortRef.current?.abort()
    }
  }, [])

  const loadDashboard = useCallback(async (): Promise<void> => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    setLoadState((previous) => (previous === "loaded" ? previous : "loading"))
    try {
      const dashboard = await fetchMonitoringDashboard(controller.signal)
      if (!mountedRef.current || controller.signal.aborted) return
      const next = toMonitoringState(dashboard)
      setState(next)
      setSelectedVehicleId((previous) => resolveSelectedVehicleId(previous, next))
      setErrorMessage(null)
      setLoadState("loaded")
    } catch (error) {
      if (isAbortError(error) || !mountedRef.current) return
      setErrorMessage(error instanceof Error ? error.message : "대시보드를 불러오지 못했습니다.")
      setLoadState("error")
    }
  }, [])

  /**
   * 화물 높이는 status 이벤트에 없고 dashboard 응답에만 있다. 그래서 **화물이 실제로 바뀐
   * 이벤트에서만** dashboard 를 다시 부른다 — 매 이벤트마다 재조회하면 초당 수십 번 왕복한다.
   * 같은 화물로 두 번 요청하지 않도록 차량별 마지막 요청 화물을 기억한다.
   */
  const requestedCargoRef = useRef<Record<string, number>>({})
  const requestCargoHeightRefresh = useCallback(
    (vehicleId: string, cargoId: number) => {
      if (requestedCargoRef.current[vehicleId] === cargoId) return
      requestedCargoRef.current[vehicleId] = cargoId
      void loadDashboard()
    },
    [loadDashboard],
  )

  const applyStatusEvent = useCallback(
    (event: RealtimeEvent<unknown>) => {
      const update = normalizeStatusEvent(event)
      if (!update) return
      setState((previous) => {
        const current = previous.vehiclesById[update.vehicleId]
        if (!current) return previous
        return {
          ...previous,
          vehiclesById: {
            ...previous.vehiclesById,
            [update.vehicleId]: {
              ...current,
              status: update.status,
              // 이벤트가 값을 실어 보냈을 때만 덮는다. null 은 "모름"이라 기존 값을 지우면 안 된다.
              hasCargo: update.hasCargo ?? current.hasCargo,
              cargoId: update.cargoId ?? current.cargoId,
              lastUpdatedAt: update.updatedAt ?? current.lastUpdatedAt,
            },
          },
        }
      })
      if (update.cargoId != null && update.cargoId !== vehiclesRef.current[update.vehicleId]?.cargoId) {
        requestCargoHeightRefresh(update.vehicleId, update.cargoId)
      }
    },
    [requestCargoHeightRefresh],
  )

  /**
   * 운반 작업 이벤트 처리.
   *
   * 실패 상세(원인 코드·전복 등급·돌출률)는 dashboard 응답에만 있으므로 **실패 이벤트에서만**
   * 재조회한다 — 모든 작업 이벤트마다 부르면 정상 흐름에서 왕복이 늘기만 한다.
   * 이 재조회가 있어야 TTL 만료처럼 사용자가 아무 조작도 하지 않은 실패가 화면에 즉시 뜬다.
   */
  const applyTaskEvent = useCallback(
    (event: TransportTaskEvent) => {
      if (event.eventType !== TRANSPORT_TASK_FAILED_EVENT_TYPE) return
      void loadDashboard()
    },
    [loadDashboard],
  )

  const applyLocationEvent = useCallback((event: RealtimeEvent<unknown>) => {
    const location = normalizeLocationEvent(event)
    if (!location) return
    setState((previous) => {
      const current = previous.vehiclesById[event.vehicleId]
      if (!current || isOlder(location.messageAt, current.location?.messageAt)) return previous
      return {
        ...previous,
        vehiclesById: {
          ...previous.vehiclesById,
          [event.vehicleId]: { ...current, location },
        },
      }
    })
  }, [])

  const vehicles = useMemo(
    () => state.vehicleOrder.map((id) => state.vehiclesById[id]).filter(Boolean),
    [state.vehicleOrder, state.vehiclesById],
  )
  const selectedVehicle = selectedVehicleId
    ? (state.vehiclesById[selectedVehicleId] ?? null)
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
    applyTaskEvent,
  }
}
