"""측정 결과 REST 전송 — 스테이션 측정 JSON → 백엔드 API (S15P11A304-91).

MQTT 발행을 대체한다(2026-07-31). 백엔드가 `fast/station/+/measurement` 구독을 없애고
`POST /api/stations/measurements`로 갈아탔다 — 브로커를 거칠 이유가 없었고(측정은
요청-응답이 자연스럽다), 저장되는 값이 네 개뿐이라 원본 전량을 실어 보낼 필요도 없었다.

**우리 측정 JSON(handoff 규격)을 백엔드 요청 6필드로 축약해 보낸다.**

| 백엔드 필드 | 원본 | 변환 |
|---|---|---|
| `measurementId` | `measurement_id` | 없음 |
| `status` | `status` | 없음 |
| `cargoHeight` | `dimensions.height_cm` | **cm ÷ 100 = m** |
| `tippingLevel` | `tipping.level` | 소문자 그대로(백엔드가 대문자로 저장) |
| `overhangRatio` | `tipping.overhang` | 없음 |
| `measuredAt` | `measured_at` | 없음 |

⚠️ **`cargoHeight`는 `height_cm`(화물만)이지 `total_height_cm`이 아니다.** 백엔드가
적재 판단에서 파렛트 높이 0.12m를 따로 더한다(`requiredHeight = cargoHeight + 0.12 +
clearance`). 총높이를 보내면 파렛트를 두 번 더하게 된다.

⚠️ **세션이 먼저 열려 있어야 한다.** `sessionId`는 보내지 않고 백엔드가 활성 세션을
찾아 붙인다. 없으면 409 `STATION_SESSION_NOT_ACTIVE` —
`POST /api/stations/sessions?cargoId=...`로 먼저 연다.

409 `STATION_MEASUREMENT_ID_DUPLICATED`는 이미 저장됐다는 뜻이라 재전송이 필요 없다.

    STATION_API_BASE=http://<백엔드> python src/station/serve.py --once --publish
"""

from __future__ import annotations

import contextlib
import json
import os
import signal
import sys
import urllib.error
import urllib.request

DEFAULT_TIMEOUT = 10.0


def base_url_of(base_url: str | None = None) -> str:
    return (base_url or os.getenv("STATION_API_BASE", "http://localhost:8080")).rstrip("/")


def _call(method: str, url: str, body: dict | None = None,
          timeout: float = DEFAULT_TIMEOUT) -> dict:
    """공통 HTTP 호출. 실패는 전부 `StationApiError`로 올린다.

    백엔드 응답 봉투는 `{"success":.., "data":.., "error":{"code":..}}`라 `data`만 꺼낸다.
    """
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"} if data else {}
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        raise StationApiError(e.code, e.read().decode("utf-8", errors="replace")) from None
    except urllib.error.URLError as e:
        raise StationApiError(0, f"연결 실패: {e.reason} ({url})") from None
    if not text:
        return {}
    payload = json.loads(text)
    return payload.get("data") if isinstance(payload, dict) and "data" in payload else payload


def _get(payload: dict, *path):
    """중첩 dict를 안전하게 판다. 중간이 없거나 null이면 None."""
    cur = payload
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def to_request(payload: dict, session_id: str | None = None) -> dict:
    """측정 JSON → 백엔드 REST 요청 7필드.

    status가 ok가 아니면 치수·전복이 없을 수 있다. 백엔드가 status별로 필수/null을
    검증하므로 여기서 임의로 채우지 않고 None 그대로 보낸다.

    ⚠️ **`sessionId`는 2026-07-31부터 필수다**(백엔드 172 TTL 도입과 함께 변경).
    종전에는 백엔드가 활성 세션을 조회해 붙였는데, TTL·강제해제로 세션 A가 풀리고 B가
    열린 뒤 도착한 **A의 늦은 측정이 B에 잘못 귀속**되는 구멍이 있었다. 요청이 자기
    출처 세션을 밝혀야 백엔드가 걸러낼 수 있다(불일치 시 409 `STATION_SESSION_MISMATCH`).

    ⚠️ **재전송할 때도 측정 시점의 session_id를 유지**해야 한다. 새 세션 값으로 바꾸면
    막으려던 오귀속이 그대로 발생한다.
    """
    height_cm = _get(payload, "dimensions", "height_cm")
    return {
        "sessionId": session_id,
        "measurementId": payload.get("measurement_id"),
        "status": payload.get("status"),
        # cm → m. 화물만의 높이다(파렛트 제외) — 백엔드가 파렛트를 따로 더한다.
        "cargoHeight": round(height_cm / 100.0, 4) if height_cm is not None else None,
        "tippingLevel": _get(payload, "tipping", "level"),
        "overhangRatio": _get(payload, "tipping", "overhang"),
        "measuredAt": payload.get("measured_at"),
    }


class StationApiError(RuntimeError):
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def post_measurement(payload: dict, base_url: str | None = None,
                     timeout: float = DEFAULT_TIMEOUT,
                     session_id: str | None = None) -> dict:
    """측정 결과를 백엔드로 POST. 성공 시 저장된 결과, 실패 시 StationApiError.

    `session_id`는 `measurement_session`이 준 값을 그대로 넘긴다. 빠지면 백엔드가
    400(`sessionId 는 필수입니다`)으로 거부한다.
    """
    return _call("POST", f"{base_url_of(base_url)}/api/stations/measurements",
                 to_request(payload, session_id), timeout)


def send(payload: dict, base_url: str | None = None,
         session_id: str | None = None) -> bool:
    """serve.py용 얇은 래퍼 — 성공 여부만 돌려주고 사유는 stderr로."""
    try:
        post_measurement(payload, base_url, session_id=session_id)
        return True
    except StationApiError as e:
        if e.status == 409 and "SESSION_MISMATCH" in e.body:
            # TTL 만료·강제해제로 내 세션이 이미 풀렸고 다른 세션이 열렸다.
            # **새 세션 id로 바꿔 재전송하면 안 된다** — 그게 막으려던 오귀속이다.
            print("[station-api] 내 세션이 더 이상 활성이 아니다(TTL 만료 등) — "
                  "이 측정은 버리고 다시 측정할 것. 세션 id를 바꿔 재전송 금지.",
                  file=sys.stderr)
            return False
        if e.status == 409 and "MEASUREMENT_ID_DUPLICATED" in e.body:
            print("[station-api] 이미 저장된 measurement_id — 재전송 불필요", file=sys.stderr)
            return True          # 중복은 실패가 아니다(이미 서버에 있다)
        if e.status == 409 and "SESSION_MEASUREMENT_ALREADY_EXISTS" in e.body:
            # 세션 하나에 측정 하나다. 이 세션은 이미 끝났으니 새 세션을 열어야 한다.
            print("[station-api] 이 세션엔 이미 측정 결과가 있다 — 세션을 닫고 새로 열 것",
                  file=sys.stderr)
            return False
        if e.status == 409 and "SESSION_NOT_ACTIVE" in e.body:
            print("[station-api] 활성 세션이 없다 — "
                  "--cargo-id 로 세션을 열고 측정할 것\n  " + e.body, file=sys.stderr)
            return False
        print(f"[station-api] 전송 실패 {e}", file=sys.stderr)
        return False


# ── 세션 ────────────────────────────────────────────────────────────────────
#
# 측정 설비는 하나뿐이라 백엔드가 **단일 점유 뮤텍스**로 지킨다(`station_state`의
# `active_session_id`). 열려 있는 동안 다른 화물은 세션을 못 연다(409 ALREADY_OCCUPIED).
#
# ⚠️ **닫지 않으면 설비가 무기한 잠긴다.** 백엔드에 TTL·자동 만료가 없다. 그래서
# `measurement_session`이 예외·Ctrl-C·SIGTERM 어느 경로로 나가든 닫기를 시도한다.
#
# ⚠️ **측정이 저장되기 전에는 닫을 수도 없다**(409 MEASUREMENT_NOT_COMPLETED). 전송이
# 실패한 채 끝나면 우리 힘으로 못 푸는 잠금이 남으므로, 그 경우를 조용히 넘기지 않고
# 세션 ID와 복구 방법을 크게 찍는다.


def open_session(cargo_id: str, base_url: str | None = None,
                 timeout: float = DEFAULT_TIMEOUT) -> str:
    """측정 세션을 열고 sessionId를 돌려준다. 이미 점유 중이면 StationApiError(409)."""
    data = _call("POST",
                 f"{base_url_of(base_url)}/api/stations/sessions?cargoId={cargo_id}",
                 None, timeout)
    session_id = (data or {}).get("sessionId")
    if not session_id:
        raise StationApiError(0, f"세션 응답에 sessionId가 없다: {data}")
    return session_id


def close_session(session_id: str, base_url: str | None = None,
                  timeout: float = DEFAULT_TIMEOUT) -> None:
    """세션을 닫는다. **측정이 저장된 뒤에만** 성공한다."""
    _call("DELETE", f"{base_url_of(base_url)}/api/stations/sessions/{session_id}",
          None, timeout)


def active_session(base_url: str | None = None,
                   timeout: float = DEFAULT_TIMEOUT) -> dict | None:
    """현재 활성 세션. 없으면 None (409를 None으로 바꿔 돌려준다)."""
    try:
        return _call("GET", f"{base_url_of(base_url)}/api/stations/sessions/active",
                     None, timeout)
    except StationApiError as e:
        if e.status in (404, 409):
            return None
        raise


class SessionNotReleased(RuntimeError):
    """세션을 닫지 못한 채 빠져나왔다 — 설비가 잠긴 상태로 남았다."""

    def __init__(self, session_id: str, reason: str) -> None:
        super().__init__(f"세션 {session_id}을 닫지 못했다: {reason}")
        self.session_id = session_id


@contextlib.contextmanager
def measurement_session(cargo_id: str, base_url: str | None = None,
                        timeout: float = DEFAULT_TIMEOUT):
    """세션을 열고, **어떤 경로로 나가든 닫기를 시도한다.**

        with measurement_session("cargo-1") as session_id:
            ...측정·전송...

    보장 범위를 정확히 적어둔다 — 과장하면 운영에서 배신당한다:

    | 종료 경로 | 닫히나 |
    |---|---|
    | 정상 종료 · 예외 · Ctrl-C | ✅ |
    | `SIGTERM` (kill, 서비스 정지) | ✅ 핸들러로 예외 전환 |
    | `SIGKILL`(kill -9) · 전원 차단 · 블루스크린 | ❌ **불가능** |
    | 측정 전송 실패 후 종료 | ❌ 백엔드가 닫아주지 않는다(아래) |

    마지막 둘은 클라이언트가 손쓸 수 없다. `--release-session`으로 사람이 푼다.

    ⚠️ 닫기 실패가 **원래 예외를 덮지 않게** 한다. 측정이 왜 실패했는지가 세션이 왜
    안 닫혔는지보다 중요하고, 원인 예외를 잃으면 진단이 어려워진다.
    """
    session_id = open_session(cargo_id, base_url, timeout)
    print(f"[station-api] 세션 열림 sessionId={session_id} cargoId={cargo_id}",
          file=sys.stderr)

    def _on_sigterm(signum, frame):     # noqa: ARG001
        # SIGTERM은 기본이 즉시 종료라 finally가 안 돈다 — 예외로 바꿔 정리 경로를 태운다.
        raise KeyboardInterrupt("SIGTERM")

    previous = None
    with contextlib.suppress(ValueError):   # 메인 스레드가 아니면 등록 불가
        previous = signal.signal(signal.SIGTERM, _on_sigterm)

    failed_to_close: SessionNotReleased | None = None
    try:
        yield session_id
    finally:
        if previous is not None:
            with contextlib.suppress(ValueError):
                signal.signal(signal.SIGTERM, previous)
        try:
            close_session(session_id, base_url, timeout)
            print(f"[station-api] 세션 닫힘 sessionId={session_id}", file=sys.stderr)
        except StationApiError as e:
            if e.status == 409 and "SESSION_NOT_ACTIVE" in e.body:
                # 이미 닫혔다 — 정상이다(재진입·중복 close).
                print(f"[station-api] 세션이 이미 닫혀 있다 sessionId={session_id}",
                      file=sys.stderr)
            else:
                failed_to_close = SessionNotReleased(session_id, e.body or str(e))
                print(
                    f"\n[station-api] ⚠️ 세션을 닫지 못했다 — **설비가 잠긴 채 남는다**\n"
                    f"  sessionId = {session_id}\n"
                    f"  사유      = {e.body or e}\n"
                    f"  복구      : python src/station/serve.py --release-session\n"
                    f"  (측정이 저장되기 전에는 백엔드가 종료를 거부한다. TTL이 없어\n"
                    f"   방치하면 다른 화물이 측정을 시작할 수 없다.)\n",
                    file=sys.stderr)
    # 원래 예외가 있었다면 이 지점에 오지 않는다 — 덮어쓰지 않는다는 뜻이다.
    if failed_to_close is not None:
        raise failed_to_close
