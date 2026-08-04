"use client"

import { useCallback, useEffect, useRef } from "react"
import { ChevronLeft, ChevronRight } from "lucide-react"
import { cn } from "@/lib/utils"

const SLIDE_LABELS = ["통합 관제", "AI 측정 영상"] as const

export function MonitoringSlider({
  activeIndex,
  onChange,
  children,
}: {
  activeIndex: number
  onChange: (index: number) => void
  children: React.ReactNode
}) {
  const pointerStartRef = useRef<number | null>(null)
  const select = useCallback((index: number) => onChange(Math.max(0, Math.min(1, index))), [onChange])

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (event.key === "Escape") {
        select(0)
        return
      }
      if (target?.matches("input, textarea, select, [contenteditable='true']")) return
      if (target?.closest("button, a, [role='button']")) return
      if (event.key === "ArrowLeft") select(activeIndex - 1)
      if (event.key === "ArrowRight") select(activeIndex + 1)
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
  }, [activeIndex, select])

  return (
    <section
      className="relative min-h-0 min-w-0 flex-1 overflow-hidden"
      aria-label="관제 화면 슬라이더"
      onPointerDown={(event) => {
        if ((event.target as HTMLElement).closest("button, a, input, textarea, select")) return
        if (event.pointerType !== "mouse" || event.button === 0) pointerStartRef.current = event.clientX
      }}
      onPointerUp={(event) => {
        const start = pointerStartRef.current
        pointerStartRef.current = null
        if (start == null) return
        const delta = event.clientX - start
        if (Math.abs(delta) < 70) return
        select(delta < 0 ? activeIndex + 1 : activeIndex - 1)
      }}
      onPointerCancel={() => {
        pointerStartRef.current = null
      }}
    >
      <div
        className="flex h-full min-h-0 transition-transform duration-300 ease-out motion-reduce:transition-none"
        style={{ transform: `translateX(-${activeIndex * 50}%)`, width: "200%" }}
      >
        {children}
      </div>

      <button
        type="button"
        onClick={() => select(activeIndex - 1)}
        disabled={activeIndex === 0}
        aria-label="이전 관제 화면"
        className="absolute left-2 top-1/2 z-40 -translate-y-1/2 rounded-full border border-slate-600 bg-slate-950/85 p-1.5 text-slate-200 shadow-lg transition hover:bg-slate-800 disabled:pointer-events-none disabled:opacity-0"
      >
        <ChevronLeft className="size-5" aria-hidden="true" />
      </button>
      <button
        type="button"
        onClick={() => select(activeIndex + 1)}
        disabled={activeIndex === 1}
        aria-label="다음 관제 화면"
        className="absolute right-2 top-1/2 z-40 -translate-y-1/2 rounded-full border border-slate-600 bg-slate-950/85 p-1.5 text-slate-200 shadow-lg transition hover:bg-slate-800 disabled:pointer-events-none disabled:opacity-0"
      >
        <ChevronRight className="size-5" aria-hidden="true" />
      </button>

      <nav className="absolute inset-x-0 bottom-1 z-40 flex justify-center gap-2" aria-label="관제 화면 선택">
        {SLIDE_LABELS.map((label, index) => (
          <button
            key={label}
            type="button"
            onClick={() => select(index)}
            aria-label={`${label} 화면 열기`}
            aria-current={activeIndex === index ? "page" : undefined}
            className={cn(
              "size-2.5 rounded-full border border-slate-300/70 transition-colors",
              activeIndex === index ? "bg-sky-400" : "bg-slate-800/80 hover:bg-slate-500",
            )}
          />
        ))}
      </nav>
    </section>
  )
}
