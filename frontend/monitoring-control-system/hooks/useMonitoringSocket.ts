"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Client, type IMessage, type StompSubscription } from "@stomp/stompjs"
import SockJS from "sockjs-client"
import { API_BASE_URL } from "@/lib/api/httpClient"
import { parseRealtimeEvent } from "@/lib/realtimeEvent"
import type { RealtimeEvent } from "@/types/websocket"
import { TOPIC_VEHICLE_LOCATION, TOPIC_VEHICLE_STATUS } from "@/types/websocket"

export interface UseMonitoringSocketOptions {
  /** false 면 연결하지 않는다(초기 로딩 실패 등으로 연결을 미룰 때 사용). */
  enabled: boolean
  onStatusEvent: (event: RealtimeEvent<unknown>) => void
  onLocationEvent: (event: RealtimeEvent<unknown>) => void
  /** STOMP 연결이 성립할 때마다 호출된다(최초 연결 포함). */
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

/**
 * 관제 실시간 구독 Hook.
 *
 * 백엔드는 STOMP endpoint 를 SockJS 로 열어 두었으므로(WebSocketConfig 의 withSockJS()),
 * ws:// 가 아니라 http(s):// 기반 URL 로 SockJS 를 만들어 연결해야 한다.
 *
 * 구독은 공통 토픽 2개만 한다. 차량별 토픽(/topic/vehicles/status/{id})은 같은 이벤트를
 * 한 번 더 받게 되므로 구독하지 않는다.
 *
 * 콜백은 ref 에 담아 최신 값을 참조한다 — 부모가 매 렌더 새 함수를 넘겨도
 * client 를 다시 만들지 않기 위해서다(enabled 만 재연결 트리거).
 */
export function useMonitoringSocket({
  enabled,
  onStatusEvent,
  onLocationEvent,
  onConnected,
  onDisconnected,
  onError,
}: UseMonitoringSocketOptions): UseMonitoringSocketResult {
  const [connected, setConnected] = useState(false)
  const [connecting, setConnecting] = useState(false)
  const [errored, setErrored] = useState(false)

  const clientRef = useRef<Client | null>(null)
  const subscriptionsRef = useRef<StompSubscription[]>([])

  // 콜백 안정화용 ref
  const handlersRef = useRef({ onStatusEvent, onLocationEvent, onConnected, onDisconnected, onError })
  useEffect(() => {
    handlersRef.current = { onStatusEvent, onLocationEvent, onConnected, onDisconnected, onError }
  }, [onStatusEvent, onLocationEvent, onConnected, onDisconnected, onError])

  /** 메시지 한 건이 잘못돼도 구독 전체가 죽지 않도록 전부 감싼다. */
  const handleMessage = useCallback(
    (message: IMessage, kind: "status" | "location") => {
      try {
        const event = parseRealtimeEvent(message.body)
        if (!event) {
          console.warn("[monitoring] 해석할 수 없는 실시간 메시지 무시")
          return
        }
        if (kind === "status") {
          handlersRef.current.onStatusEvent(event)
        } else {
          handlersRef.current.onLocationEvent(event)
        }
      } catch (error) {
        console.error("[monitoring] 실시간 메시지 처리 실패:", error)
      }
    },
    [],
  )

  useEffect(() => {
    if (!enabled) {
      return
    }
    // React StrictMode 의 이중 mount 에서도 client 가 중복 생성되지 않게 방어한다.
    if (clientRef.current) {
      return
    }

    setConnecting(true)
    setErrored(false)

    const client = new Client({
      // SockJS 는 http(s) URL 을 받는다. brokerURL(ws://)을 쓰면 안 된다.
      webSocketFactory: () => new SockJS(`${API_BASE_URL}/ws`) as unknown as WebSocket,
      reconnectDelay: 5000,
      debug:
        process.env.NODE_ENV === "development"
          ? (msg: string) => console.debug("[stomp]", msg)
          : () => undefined,
    })

    client.onConnect = () => {
      setConnected(true)
      setConnecting(false)
      setErrored(false)

      // 재연결마다 이 콜백이 다시 호출되므로 매번 새로 구독한다.
      subscriptionsRef.current.forEach((sub) => {
        try {
          sub.unsubscribe()
        } catch {
          /* 이미 끊긴 구독은 무시 */
        }
      })
      subscriptionsRef.current = [
        client.subscribe(TOPIC_VEHICLE_STATUS, (message) => handleMessage(message, "status")),
        client.subscribe(TOPIC_VEHICLE_LOCATION, (message) => handleMessage(message, "location")),
      ]

      void handlersRef.current.onConnected?.()
    }

    client.onWebSocketClose = () => {
      setConnected(false)
      setConnecting(false)
      subscriptionsRef.current = []
      handlersRef.current.onDisconnected?.()
    }

    client.onDisconnect = () => {
      setConnected(false)
      setConnecting(false)
    }

    client.onStompError = (frame) => {
      setErrored(true)
      setConnecting(false)
      const message = frame.headers["message"] ?? "STOMP 오류가 발생했습니다."
      console.error("[monitoring] STOMP error:", message, frame.body)
      handlersRef.current.onError?.(new Error(message))
    }

    client.onWebSocketError = (event) => {
      setErrored(true)
      setConnecting(false)
      console.error("[monitoring] WebSocket error:", event)
      handlersRef.current.onError?.(new Error("실시간 서버에 연결할 수 없습니다."))
    }

    clientRef.current = client
    client.activate()

    return () => {
      subscriptionsRef.current.forEach((sub) => {
        try {
          sub.unsubscribe()
        } catch {
          /* 이미 끊긴 구독은 무시 */
        }
      })
      subscriptionsRef.current = []
      clientRef.current = null
      void client.deactivate()
      setConnected(false)
      setConnecting(false)
    }
  }, [enabled, handleMessage])

  const disconnect = useCallback(async () => {
    const client = clientRef.current
    clientRef.current = null
    subscriptionsRef.current = []
    if (client) {
      await client.deactivate()
    }
    setConnected(false)
    setConnecting(false)
  }, [])

  return { connected, connecting, errored, disconnect }
}
