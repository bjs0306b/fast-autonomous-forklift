"""레포 루트 `.env`를 환경변수로 올린다.

이 프로젝트의 설정 관례가 `.env` 파일이다(`.env.example`이 루트에 있고
`.gitignore`가 `.env`를 막는다). 스테이션만 "셸에서 export 하라"를 요구하면
다른 구성요소와 어긋나고, 설정했는데 왜 안 되냐로 시간을 쓰게 된다.

`python-dotenv`를 쓰지 않는 이유는 이 env가 numpy 1.x·opencv 4.10 고정으로
간신히 맞춰져 있어(CLAUDE.md) 의존성을 늘리고 싶지 않아서다. 필요한 기능이
"key=value를 읽어 환경변수로" 뿐이라 직접 읽는다.
"""

from __future__ import annotations

import os
from pathlib import Path

# 이 파일이 `ai/src/station/envfile.py` 이므로 parents[3] 이 레포 루트다.
REPO_ROOT = Path(__file__).resolve().parents[3]


def load_dotenv(path: Path | None = None) -> int:
    """`.env`를 읽어 환경변수로 올리고 올린 개수를 돌려준다.

    ⚠️ **이미 설정된 환경변수를 덮어쓰지 않는다.** 셸에서 준 값이 파일보다
    우선이어야 일회성 오버라이드가 가능하다.

    파일이 없으면 조용히 0을 돌려준다 — 설정 없이도 기본값으로 돌아야 한다.
    """
    target = REPO_ROOT / ".env" if path is None else path
    if not target.is_file():
        return 0
    loaded = 0
    for raw in target.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if not key or key in os.environ:
            continue
        os.environ[key] = value
        loaded += 1
    return loaded


# --- MQTT 접속 설정 (스테이션 수신·트리거 발행이 같은 값을 써야 한다) ---

def mqtt_settings() -> dict:
    """브로커 접속에 필요한 값을 환경변수에서 모아 준다.

    ⚠️ 브로커 주소 기본값이 **호스트명이 아니라 IP**다. 서버 인증서 SAN이
    `IP Address:3.38.178.143` 뿐이고 DNS 이름이 없어서, `i15a304.p.ssafy.io`로
    붙으면 호스트명 검증에서 막힌다(둘은 같은 서버다 — 실측 확인).
    인증서를 DNS SAN으로 재발급하면 호스트명으로 바꿀 수 있다.
    """
    return {
        "broker": os.environ.get("STATION_MQTT_BROKER", "3.38.178.143"),
        "port": int(os.environ.get("STATION_MQTT_PORT", "8883")),
        # CA는 **레포 루트** `infra/`에 있다(`ai/infra/` 아님). Dockerfile.backend도
        # 같은 경로를 COPY 하므로 백엔드·스테이션이 같은 인증서를 본다.
        "ca": os.environ.get("STATION_MQTT_CA",
                             str(REPO_ROOT / "infra" / "mqtt-ca.crt")),
        "user": os.environ.get("STATION_MQTT_USER"),
        "password": os.environ.get("STATION_MQTT_PASSWORD"),
    }
