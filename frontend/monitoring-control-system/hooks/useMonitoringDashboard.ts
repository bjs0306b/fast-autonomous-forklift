"use client"

import { useCallback, useEffect, useMemo, useRef, useState } from "react"
import { fetchMonitoringDashboard } from "@/lib/api/monitoringApi"
import { normalizeLocationEvent, normalizeStatusEvent } from "@/lib/realtimeEvent"
import { isAbortError } from "@/types/api"
import type { DashboardResponse, DashboardTask, DashboardVehicle } from "@/types/monitoring"
import type { RealtimeEvent } from "@/types/websocket"

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
  return { vehiclesById, vehicleOrder, tasksById }
}

function resolveSelectedVehicleId(previous: string | null, state: MonitoringState): string | null {
  if (previous && state.vehiclesById[previous]) return previous
  const located = state.vehicleOrder.find((id) => {
    const location = state.vehiclesById[id]?.location
    return location?.x != null && location?.y != null
  })
  return located ?? state.vehicleOrder[0] ?? null
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
  }
}
