// 백엔드 공통 응답 봉투(ApiResponse) 타입.
// Spring Boot 의 com.fast.backend.common.api.ApiResponse / ErrorResponse 와 1:1 대응한다.
//
// 주의: HTTP 200 이어도 success=false 이거나 data=null 일 수 있다.
// 따라서 상태 코드만 보고 성공으로 판단하면 안 된다(httpClient 참고).

export interface ApiError {
  /** 백엔드 ErrorCode enum 이름 (예: VEHICLE_NOT_FOUND) */
  code: string
  message: string
}

export interface ApiResponse<T> {
  success: boolean
  data: T | null
  error: ApiError | null
}

/**
 * API 호출 실패를 나타내는 에러.
 * 네트워크 실패 / HTTP 오류 / 백엔드 업무 오류를 하나의 타입으로 다루되,
 * 원인을 구분할 수 있도록 kind 를 둔다.
 */
export type ApiRequestErrorKind =
  /** fetch 자체 실패 (서버 미기동, DNS, CORS 등) */
  | "network"
  /** HTTP 상태 코드가 2xx 가 아님 */
  | "http"
  /** 응답 본문을 JSON 으로 파싱할 수 없음 */
  | "parse"
  /** HTTP 는 성공했지만 success=false 이거나 data=null */
  | "business"

export class ApiRequestError extends Error {
  readonly kind: ApiRequestErrorKind
  readonly status?: number
  readonly code?: string

  constructor(
    message: string,
    kind: ApiRequestErrorKind,
    options?: { status?: number; code?: string; cause?: unknown },
  ) {
    super(message)
    this.name = "ApiRequestError"
    this.kind = kind
    this.status = options?.status
    this.code = options?.code
    if (options?.cause !== undefined) {
      this.cause = options.cause
    }
  }
}

/** 사용자가 화면을 벗어나 요청이 취소된 경우인지 판별한다(오류로 표시하면 안 됨). */
export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError"
}
