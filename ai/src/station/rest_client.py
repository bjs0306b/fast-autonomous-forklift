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
| `sessionId` | (측정 시점에 고정한 세션) | 호출자가 넘긴다 |

⚠️ **`cargoHeight`는 `height_cm`(화물만)이지 `total_height_cm`이 아니다.** 백엔드가
적재 판단에서 파렛트 높이 0.12m를 따로 더한다(`requiredHeight = cargoHeight + 0.12 +
clearance`). 총높이를 보내면 파렛트를 두 번 더하게 된다.

⚠️ **`sessionId`는 필수다(2026-07-31 계약 변경).** 예전에는 보내지 않고 백엔드가 활성 세션을
찾아 붙였는데, 그러면 세션 A가 TTL·force로 해제되고 세션 B가 열린 뒤 도착한 A의 늦은 측정이
**B에 잘못 귀속**된다. 이제 요청이 자기 출처 세션을 밝히고 백엔드가 대조한다.

**측정 시점의 sessionId를 고정해 그대로 쓴다.** 전송 직전에 "지금 활성 세션"을 다시 읽으면
안 된다 — 그게 바로 오귀속을 만드는 경로다. 재전송에서도 원래 값을 유지한다.

세션은 `POST /api/stations/sessions?cargoId=...`로 먼저 열고(`open_session()`),
응답의 `sessionId`를 측정과 함께 들고 다닌다.

**409 응답 구분**
- `STATION_MEASUREMENT_ID_DUPLICATED` — 이미 저장됨. 재전송 불필요(성공으로 본다)
- `STATION_SESSION_MISMATCH` — 이 측정의 세션이 더 이상 활성이 아니다. **stale**
- `STATION_SESSION_NOT_ACTIVE` — 활성 세션 없음. **stale**

stale은 **재시도하지 않는다.** 새 sessionId로 바꿔 재전송하면 막으려던 오귀속이 발생한다.

    STATION_API_BASE=http://<백엔드> python src/station/serve.py --once --publish
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_TIMEOUT = 10.0


def _get(payload: dict, *path):
    """중첩 dict를 안전하게 판다. 중간이 없거나 null이면 None."""
    cur = payload
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def to_request(payload: dict, session_id: str) -> dict:
    """측정 JSON + 세션 → 백엔드 REST 요청 7필드.

    status가 ok가 아니면 치수·전복이 없을 수 있다. 백엔드가 status별로 필수/null을
    검증하므로 여기서 임의로 채우지 않고 None 그대로 보낸다.

    `session_id`는 **인자로 받는다.** 전역/환경변수에서 다시 읽지 않는 것이 핵심이다 —
    측정 생성 시점의 세션을 그대로 들고 와야 늦은 측정이 새 세션에 섞이지 않는다.
    """
    if not session_id or not str(session_id).strip():
        # 여기서 막지 않으면 sessionId 없이 POST 해서 400을 받는다. 원인이 서버 응답보다
        # 이 자리에서 드러나는 편이 낫다.
        raise ValueError("session_id 가 필요하다 — 세션 생성 응답의 sessionId 를 넘길 것")
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


def _base_url(base_url: str | None) -> str:
    return (base_url or os.getenv("STATION_API_BASE", "http://localhost:8080")).rstrip("/")


def open_session(cargo_id: str, base_url: str | None = None,
                 timeout: float = DEFAULT_TIMEOUT) -> str:
    """측정 세션을 열고 **백엔드가 발급한 sessionId** 를 돌려준다.

    이 값을 측정과 함께 보관했다가 전송에 쓴다. 설비가 이미 점유 중이면
    409 `STATION_ALREADY_OCCUPIED` 다(TTL 이 지나면 다음 시도에서 자동 회수된다).
    """
    url = f"{_base_url(base_url)}/api/stations/sessions?cargoId={urllib.parse.quote(cargo_id)}"
    req = urllib.request.Request(url, data=b"", method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace") or "{}")
    except urllib.error.HTTPError as e:
        raise StationApiError(e.code, e.read().decode("utf-8", errors="replace")) from None
    except urllib.error.URLError as e:
        raise StationApiError(0, f"연결 실패: {e.reason} ({url})") from None
    session_id = (data.get("data") or {}).get("sessionId")
    if not session_id:
        raise StationApiError(0, f"세션 응답에 sessionId 가 없다: {data}")
    return session_id


def post_measurement(payload: dict, session_id: str, base_url: str | None = None,
                     timeout: float = DEFAULT_TIMEOUT) -> dict:
    """측정 결과를 백엔드로 POST. 성공 시 응답 dict, 실패 시 StationApiError."""
    url = f"{_base_url(base_url)}/api/stations/measurements"
    body = json.dumps(to_request(payload, session_id), ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            text = resp.read().decode("utf-8", errors="replace")
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        # 409는 호출자가 구분해서 다룰 수 있게 상태코드를 실어 올린다.
        raise StationApiError(e.code, detail) from None
    except urllib.error.URLError as e:
        raise StationApiError(0, f"연결 실패: {e.reason} ({url})") from None


#: 재시도해도 결과가 달라지지 않는 백엔드 오류(= stale). 새 세션으로 바꿔 재전송하면
#: 막으려던 오귀속이 발생하므로, 호출자는 이 분류를 보고 재시도를 **중단**해야 한다.
STALE_ERROR_CODES = ("STATION_SESSION_MISMATCH", "STATION_SESSION_NOT_ACTIVE")


def is_stale_rejection(error: StationApiError) -> bool:
    """이 측정이 더 이상 유효하지 않은 세션의 것이라 거부됐는가."""
    return error.status == 409 and any(code in error.body for code in STALE_ERROR_CODES)


def send(payload: dict, session_id: str, base_url: str | None = None) -> bool:
    """serve.py용 얇은 래퍼 — 성공 여부만 돌려주고 사유는 stderr로.

    `session_id` 는 **측정 시점에 고정된 값**이어야 한다. 재전송할 때도 같은 값을 넘긴다 —
    현재 활성 세션을 다시 읽어 넣으면 늦은 측정이 새 세션에 귀속된다.
    """
    measurement_id = payload.get("measurement_id")
    try:
        post_measurement(payload, session_id, base_url)
        print(f"[station-api] Measurement upload: measurementId={measurement_id}, "
              f"sessionId={session_id}", file=sys.stderr)
        return True
    except StationApiError as e:
        if e.status == 409 and "DUPLICATED" in e.body:
            print("[station-api] 이미 저장된 measurement_id — 재전송 불필요", file=sys.stderr)
            return True          # 중복은 실패가 아니다(이미 서버에 있다)
        if is_stale_rejection(e):
            # 재시도 금지. sessionId 를 바꿔 다시 보내면 안 된다.
            print(f"[station-api] Measurement upload rejected as stale — "
                  f"measurementId={measurement_id}, sessionId={session_id}, http={e.status}",
                  file=sys.stderr)
            print("  이 측정은 해제된 세션의 것이다. 새 세션으로 재전송하지 말 것.",
                  file=sys.stderr)
            print(f"  {e.body}", file=sys.stderr)
        else:
            print(f"[station-api] 전송 실패 {e}", file=sys.stderr)
        return False
