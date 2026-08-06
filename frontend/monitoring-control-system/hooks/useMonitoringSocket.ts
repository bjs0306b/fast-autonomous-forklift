"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs"
import SockJS from "sockjs-client"
import { API_BASE_URL } from "@/lib/api/httpClient"
import { parseRealtimeEvent, parseTransportTaskEvent } from "@/lib/realtimeEvent"
import type { RealtimeEvent, TransportTaskEvent } from "@/types/websocket"
import {
  TOPIC_TRANSPORT_TASKS,
  TOPIC_VEHICLE_LOCATION,
  TOPIC_VEHICLE_PATH,
  TOPIC_VEHICLE_RESULT,
  TOPIC_VEHICLE_STATUS,
} from "@/types/websocket"

export interface UseMonitoringSocketOptions {
  enabled: boolean
  onStatusEvent: (event: RealtimeEvent<unknown>) => void
  onLocationEvent: (event: RealtimeEvent<unknown>) => void
  /** 운반 작업 이벤트(실패 포함). 백엔드가 이미 발행하던 토픽을 구독만 추가한다. */
  onTaskEvent?: (event: TransportTaskEvent) => void
  onPathEvent?: (event: RealtimeEvent<unknown>) => void
  onCommandResultEvent?: (event: RealtimeEvent<unknown>) => void
  onConnected?: () => void | Promise<void>
  onDisconnected?: () => void
  onError?: (error: Error) => void
}

export interface UseMonitoringSocketResult {
  connected: boolean
  connecting: boolean
  errored: boolean
  disconnect: () => Promise<void>
}

export function useMonitoringSocket({
  enabled,
  onStatusEvent,
  onLocationEvent,
  onTaskEvent,
  onPathEvent,
  onCommandResultEvent,
  onConnected,
  onDisconnected,
  onError,
}: UseMonitoringSocketOptions): UseMonitoringSocketResult {
  const [connected, setConnected] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [errored, setErrored] = useState(false)
  const clientRef = useRef<Client | null>(null)
  const subscriptionsRef = useRef<StompSubscription[]>([])
  const handlersRef = useRef({
    onStatusEvent,
    onLocationEvent,
    onTaskEvent,
    onPathEvent,
    onCommandResultEvent,
    onConnected,
    onDisconnected,
    onError,
  })

  useEffect(() => {
    handlersRef.current = {
      onStatusEvent,
      onLocationEvent,
      onTaskEvent,
      onPathEvent,
      onCommandResultEvent,
      onConnected,
      onDisconnected,
      onError,
    }
  }, [onStatusEvent, onLocationEvent, onTaskEvent, onPathEvent, onCommandResultEvent, onConnected, onDisconnected, onError])

  const handleMessage = useCallback((message: IMessage, kind: "status" | "location") => {
    const event = parseRealtimeEvent(message.body)
    if (!event) return
    if (kind === "status") handlersRef.current.onStatusEvent(event)
    else handlersRef.current.onLocationEvent(event)
  }, [])

  const handleTaskMessage = useCallback((message: IMessage) => {
    const event = parseTransportTaskEvent(message.body)
    if (!event) return
    handlersRef.current.onTaskEvent?.(event)
  }, [])

  const handleAdditionalVehicleMessage = useCallback(
    (message: IMessage, kind: "path" | "result") => {
      const event = parseRealtimeEvent(message.body)
      if (!event) return
      if (kind === "path") handlersRef.current.onPathEvent?.(event)
      else handlersRef.current.onCommandResultEvent?.(event)
    },
    [],
  )

  useEffect(() => {
    if (!enabled || clientRef.current) return
    setConnecting(true)
    setErrored(false)
    const client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE_URL}/ws`) as unknown as WebSocket,
      reconnectDelay: 5000,
      debug: process.env.NODE_ENV === "development"
        ? (message: string) => console.debug("[stomp]", message)
        : () => undefined,
    })
    client.onConnect = () => {
      setConnected(true)
      setConnecting(false)
      setErrored(false)
      subscriptionsRef.current.forEach((subscription) => subscription.unsubscribe())
      subscriptionsRef.current = [
        client.subscribe(TOPIC_VEHICLE_STATUS, (message) => handleMessage(message, "status")),
        client.subscribe(TOPIC_VEHICLE_LOCATION, (message) => handleMessage(message, "location")),
        client.subscribe(TOPIC_TRANSPORT_TASKS, handleTaskMessage),
      ]
      if (handlersRef.current.onPathEvent) {
        subscriptionsRef.current.push(
          client.subscribe(TOPIC_VEHICLE_PATH, (message) =>
            handleAdditionalVehicleMessage(message, "path"),
          ),
        )
      }
      if (handlersRef.current.onCommandResultEvent) {
        subscriptionsRef.current.push(
          client.subscribe(TOPIC_VEHICLE_RESULT, (message) =>
            handleAdditionalVehicleMessage(message, "result"),
          ),
        )
      }
      void handlersRef.current.onConnected?.()
    }
    client.onWebSocketClose = () => {
      setConnected(false)
      setConnecting(false)
      subscriptionsRef.current = []
      handlersRef.current.onDisconnected?.()
    }
    client.onStompError = (frame) => {
      setErrored(true)
      setConnecting(false)
      handlersRef.current.onError?.(new Error(frame.headers.message ?? "STOMP 오류"))
    }
    client.onWebSocketError = () => {
      setErrored(true)
      setConnecting(false)
      handlersRef.current.onError?.(new Error("실시간 서버에 연결할 수 없습니다."))
    }
    clientRef.current = client
    client.activate()
    return () => {
      subscriptionsRef.current.forEach((subscription) => subscription.unsubscribe())
      subscriptionsRef.current = []
      clientRef.current = null
      void client.deactivate()
    }
  }, [enabled, handleMessage, handleTaskMessage, handleAdditionalVehicleMessage])

  const disconnect = useCallback(async () => {
    subscriptionsRef.current.forEach((subscription) => subscription.unsubscribe())
    subscriptionsRef.current = []
    const client = clientRef.current
    clientRef.current = null
    if (client) await client.deactivate()
    setConnected(false)
    setConnecting(false)
  }, [])

  return { connected, connecting, errored, disconnect }
}
