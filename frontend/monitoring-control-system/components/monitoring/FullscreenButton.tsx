"use client"

import { Maximize2, Minimize2 } from "lucide-react"

export function FullscreenButton({
  isFullscreen,
  onToggle,
}: {
  isFullscreen: boolean
  onToggle: () => void
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      className="inline-flex size-7 items-center justify-center rounded-md bg-slate-900/80 text-slate-200 shadow-sm ring-1 ring-white/10 backdrop-blur-sm transition-colors hover:bg-slate-800 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sky-400"
      aria-label={isFullscreen ? "전체 화면 종료" : "전체 화면"}
      aria-pressed={isFullscreen}
    >
      {isFullscreen ? (
        <Minimize2 className="size-3.5" aria-hidden="true" />
      ) : (
        <Maximize2 className="size-3.5" aria-hidden="true" />
      )}
    </button>
  )
}

export default FullscreenButton
