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

import json
import os
import sys
import urllib.error
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


def to_request(payload: dict) -> dict:
    """측정 JSON → 백엔드 REST 요청 6필드.

    status가 ok가 아니면 치수·전복이 없을 수 있다. 백엔드가 status별로 필수/null을
    검증하므로 여기서 임의로 채우지 않고 None 그대로 보낸다.
    """
    height_cm = _get(payload, "dimensions", "height_cm")
    return {
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
                     timeout: float = DEFAULT_TIMEOUT) -> dict:
    """측정 결과를 백엔드로 POST. 성공 시 응답 dict, 실패 시 StationApiError."""
    base = (base_url or os.getenv("STATION_API_BASE", "http://localhost:8080")).rstrip("/")
    url = f"{base}/api/stations/measurements"
    body = json.dumps(to_request(payload), ensure_ascii=False).encode("utf-8")
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


def send(payload: dict, base_url: str | None = None) -> bool:
    """serve.py용 얇은 래퍼 — 성공 여부만 돌려주고 사유는 stderr로."""
    try:
        post_measurement(payload, base_url)
        return True
    except StationApiError as e:
        if e.status == 409 and "DUPLICATED" in e.body:
            print(f"[station-api] 이미 저장된 measurement_id — 재전송 불필요", file=sys.stderr)
            return True          # 중복은 실패가 아니다(이미 서버에 있다)
        if e.status == 409:
            print(f"[station-api] 활성 세션이 없다 — "
                  f"POST /api/stations/sessions?cargoId=... 로 먼저 열 것\n  {e.body}",
                  file=sys.stderr)
        else:
            print(f"[station-api] 전송 실패 {e}", file=sys.stderr)
        return False
