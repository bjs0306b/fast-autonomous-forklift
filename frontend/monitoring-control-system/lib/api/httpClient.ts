import type { ApiResponse } from "@/types/api"
import { ApiRequestError } from "@/types/api"

// 백엔드 REST 호출 공통 처리.
//
// 주의(백엔드 계약): HTTP 200 이어도 body.success 가 false 이거나 body.data 가 null 일 수 있다.
// (예: GET /api/vehicles/{id}/location/latest 는 위치 미수신 시 200 + data:null)
// 따라서 response.ok 만 보고 성공으로 판단하지 않는다.

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "")
}

/**
 * 백엔드 base URL.
 *
 * NOTE: 이 프로젝트는 Vite 가 아니라 Next.js 이므로 import.meta.env.VITE_* 를 쓸 수 없다.
 * 브라우저 번들에 값이 주입되려면 NEXT_PUBLIC_ 접두사가 필요하다(.env.local 참고).
 */
export const API_BASE_URL = trimTrailingSlash(
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080",
)

/** base URL 과 경로를 슬래시 중복 없이 이어 붙인다. */
export function apiUrl(path: string): string {
  return `${API_BASE_URL}/${path.replace(/^\/+/, "")}`
}

/**
 * ApiResponse 봉투를 벗겨 data 를 돌려준다.
 * 실패는 전부 ApiRequestError 로 통일해 던지며, 호출 취소(AbortError)는 그대로 통과시킨다.
 */
export async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  return requestJson<T>(path, { method: "GET" }, signal)
}

/**
 * POST 후 ApiResponse 봉투를 벗겨 data 를 돌려준다.
 *
 * {@code body} 가 undefined 면 <b>본문 없이</b> 보낸다 — 백엔드 안전 명령 API 는
 * {@code @RequestBody(required = false)} 라 빈 {@code {}} 를 굳이 보낼 필요가 없다.
 *
 * <b>주의</b>: 여기서 성공을 판정하는 것은 HTTP status 와 ApiResponse.success 까지다.
 * 안전 명령의 "발행 성공" 여부는 응답 본문의 {@code data.status}(PUBLISHED / PUBLISH_FAILED)로
 * 호출자가 따로 판단해야 한다 — PUBLISH_FAILED 도 HTTP 201 로 내려온다.
 */
export async function postJson<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const hasBody = body !== undefined && body !== null
  return requestJson<T>(
    path,
    {
      method: "POST",
      headers: hasBody ? { "Content-Type": "application/json" } : undefined,
      body: hasBody ? JSON.stringify(body) : undefined,
    },
    signal,
  )
}

interface RequestOptions {
  method: "GET" | "POST"
  headers?: Record<string, string>
  body?: string
}

async function requestJson<T>(
  path: string,
  options: RequestOptions,
  signal?: AbortSignal,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(apiUrl(path), {
      method: options.method,
      headers: { Accept: "application/json", ...(options.headers ?? {}) },
      body: options.body,
      signal,
    })
  } catch (error) {
    // 사용자가 화면을 벗어나 요청이 취소된 경우는 오류가 아니므로 그대로 던져 호출자가 구분하게 한다.
    if (error instanceof DOMException && error.name === "AbortError") {
      throw error
    }
    throw new ApiRequestError(
      "백엔드에 연결할 수 없습니다. 서버가 실행 중인지 확인해 주세요.",
      "network",
      { cause: error },
    )
  }

  let body: ApiResponse<T>
  try {
    body = (await response.json()) as ApiResponse<T>
  } catch (error) {
    if (!response.ok) {
      throw new ApiRequestError(
        `요청이 실패했습니다 (HTTP ${response.status}).`,
        "http",
        { status: response.status, cause: error },
      )
    }
    throw new ApiRequestError("서버 응답을 해석할 수 없습니다.", "parse", { cause: error })
  }

  if (!response.ok) {
    // 백엔드 GlobalExceptionHandler 는 오류에도 ApiResponse 형태를 유지하므로 메시지를 살려 쓴다.
    throw new ApiRequestError(
      body?.error?.message ?? `요청이 실패했습니다 (HTTP ${response.status}).`,
      "http",
      { status: response.status, code: body?.error?.code },
    )
  }

  if (!body || body.success !== true) {
    throw new ApiRequestError(
      body?.error?.message ?? "요청이 실패했습니다.",
      "business",
      { status: response.status, code: body?.error?.code },
    )
  }

  if (body.data === null || body.data === undefined) {
    throw new ApiRequestError("서버가 빈 응답을 반환했습니다.", "business", {
      status: response.status,
    })
  }

  return body.data
}
