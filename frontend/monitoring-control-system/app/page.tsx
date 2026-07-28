"use client"

import { useMemo, useState } from "react"
import { MainRealtimeMonitoringView } from "@/components/monitoring/MainRealtimeMonitoringView"
import { MiniMap } from "@/components/monitoring/MiniMap"
import { VehicleDetailPanel } from "@/components/monitoring/VehicleDetailPanel"
import { GlobalEmergencyStopBar } from "@/components/monitoring/GlobalEmergencyStopBar"
import type {
  MockVehicle,
  SelectedVehicleSummary,
  StreamConnectionStatus,
} from "@/types/monitoring"

// Mock 차량 (실제 API/WebSocket 연동 전 시각화용)
// bounds: minX 0, maxX 30, minY 0, maxY 20 (DEFAULT_WORLD_BOUNDS)
const MOCK_VEHICLES: MockVehicle[] = [
  {
    vehicleId: "REAL-F01",
    name: "지게차 F01",
    shortLabel: "F01",
    status: "MOVING",
    source: "REAL",
    location: { x: 10.5, y: 9 },
    heading: 45,
    speed: 1.4,
    currentTask: "팔레트 이송",
  },
  {
    vehicleId: "REAL-F02",
    name: "지게차 F02",
    shortLabel: "F02",
    status: "IDLE",
    source: "REAL",
    location: { x: 4.5, y: 16 },
    heading: 180,
    speed: 0,
    currentTask: null,
  },
  {
    vehicleId: "SIM-F01",
    name: "시뮬 지게차 S01",
    shortLabel: "S01",
    status: "WORKING",
    source: "SIMULATION",
    location: { x: 24, y: 13 },
    heading: 270,
    speed: 0.6,
    currentTask: "적재 중",
  },
  {
    // 위치 미수신(null) → 미니맵에 표시되지 않음
    vehicleId: "SIM-F02",
    name: "시뮬 지게차 S02",
    shortLabel: "S02",
    status: "ESTOP",
    source: "SIMULATION",
    location: null,
    heading: null,
    speed: null,
    currentTask: null,
  },
]

const STREAM_OPTIONS: { value: StreamConnectionStatus; label: string }[] = [
  { value: "idle", label: "미연결" },
  { value: "connecting", label: "연결 중" },
  { value: "connected", label: "연결됨" },
  { value: "error", label: "연결 실패" },
]

export default function MonitoringPage() {
  const [selectedVehicleId, setSelectedVehicleId] = useState<string | null>("REAL-F01")
  const [streamStatus, setStreamStatus] = useState<StreamConnectionStatus>("idle")

  const selectedVehicle = useMemo(
    () => MOCK_VEHICLES.find((v) => v.vehicleId === selectedVehicleId) ?? null,
    [selectedVehicleId],
  )

  const selectedSummary: SelectedVehicleSummary | null = useMemo(() => {
    if (!selectedVehicle) return null
    return {
      vehicleId: selectedVehicle.vehicleId,
      name: selectedVehicle.name,
      source: selectedVehicle.source,
      status: selectedVehicle.status,
      currentTask: selectedVehicle.currentTask ?? null,
    }
  }, [selectedVehicle])

  return (
    <main className="flex min-h-svh w-full flex-col gap-3 bg-slate-950 p-3 text-slate-100">
      <header className="flex items-center justify-between gap-3">
        <div>
          <h1 className="text-base font-semibold text-balance">디지털 트윈 실시간 관제</h1>
          <p className="text-xs text-slate-400">
            좌측 영상 · 우측 상세/미니맵 · 실제 Isaac Sim 영상 연결 전 스캐폴딩
          </p>
        </div>

        {/* 개발 전용 스트림 상태 컨트롤 (실제 관제 화면에는 포함되지 않음) */}
        <label className="flex items-center gap-2 text-xs text-slate-400">
          <span className="hidden sm:inline">dev · 영상 상태</span>
          <select
            value={streamStatus}
            onChange={(e) => setStreamStatus(e.target.value as StreamConnectionStatus)}
            className="rounded-md border border-slate-700 bg-slate-900 px-2 py-1 text-xs text-slate-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-400"
          >
            {STREAM_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </label>
      </header>

      {/* 전체 비상정지 바 */}
      <GlobalEmergencyStopBar
        onTriggerAll={() => console.log("[v0] global emergency stop")}
      />

      {/* 관제 본문: 좌측 영상 / 우측 상세+미니맵 */}
      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-[2fr_1fr]">
        {/* 좌측: 디지털 트윈 영상 전용 영역 */}
        <div className="flex min-h-0 flex-col">
          <MainRealtimeMonitoringView
            streamStatus={streamStatus}
            onRetryConnection={() => setStreamStatus("connecting")}
            selectedVehicle={selectedSummary}
          />
        </div>

        {/* 우측: 상세 패널(위) + 미니맵(아래) */}
        <div className="grid min-h-0 grid-rows-2 gap-3">
          <VehicleDetailPanel
            vehicle={selectedVehicle}
            onStop={(id) => console.log("[v0] stop", id)}
            onEmergencyStop={(id) => console.log("[v0] emergency stop", id)}
          />
          <MiniMap
            vehicles={MOCK_VEHICLES}
            selectedVehicleId={selectedVehicleId}
            onSelectVehicle={setSelectedVehicleId}
          />
        </div>
      </div>
    </main>
  )
}
