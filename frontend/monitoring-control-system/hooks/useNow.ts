"use client"

import { useEffect, useState } from "react"

/**
 * 일정 주기로 갱신되는 현재 시각(ms).
 *
 * "마지막 수신으로부터 N초 전"은 **새 이벤트가 오지 않아도 늘어나야** 의미가 있다. 통신이 끊긴
 * 상황이 정확히 그렇다 — 이벤트가 안 오므로 이벤트 기반 렌더링만으로는 화면이 "방금"에 굳어 버린다.
 * 그래서 시간 자체를 상태로 두고 흘려보낸다.
 *
 * StrictMode 에서 effect 가 두 번 실행돼도 cleanup 이 먼저 이전 타이머를 지우므로 누적되지 않는다.
 */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timerId = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(timerId)
  }, [intervalMs])

  return now
}

export default useNow
