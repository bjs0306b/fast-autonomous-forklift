package com.fast.backend.loadsafety.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.OffsetDateTime;

/**
 * {@code forklift/{vehicleId}/load-safety} 토픽으로 수신되는 적재 화물 안전 메시지
 * (prompt63.md 4장·5장 계약).
 *
 * <pre>
 * {
 *   "vehicleId": "REAL-F01",
 *   "cargoId": "CARGO-001",
 *   "forkHeight": 0.86,
 *   "cargoHeight": 1.42,
 *   "roll": 7.4,
 *   "pitch": 3.1,
 *   "loadOffsetX": -0.18,
 *   "loadOffsetY": 0.04,
 *   "riskLevel": "WARNING",
 *   "riskCode": "LOAD_TILT_EXCEEDED",
 *   "message": "화물이 좌측으로 과도하게 기울었습니다.",
 *   "source": "VISION",
 *   "detectedAt": "2026-07-29T10:30:00+09:00"
 * }
 * </pre>
 *
 * <p><b>필수</b>: {@code vehicleId}, {@code riskLevel}, {@code detectedAt}. 나머지는 전부 선택이다 —
 * 비전이 화물을 찾지 못했거나 IMU가 없는 차량도 "위험 아님"을 보고할 수 있어야 하기 때문이다.
 *
 * <p><b>식별자 alias</b>: 이 프로젝트의 기존 실물/Isaac 메시지가 {@code forkliftId}를 쓰는 사례가 있어
 * ({@code EmbeddedForkStatusMessage}, {@code IsaacForkliftStatusMessage}) 읽기 호환으로 alias를 둔다.
 * 새 규격의 정식 키는 {@code vehicleId}다(prompt32.md 1장 2번 방침).
 *
 * <p><b>단위</b>(계약 가정 — prompt63.md에 단위 표기가 없다): 높이 m, roll/pitch degree,
 * loadOffsetX/Y m. 백엔드는 단위를 변환하지 않고 받은 숫자를 그대로 보존하므로, 송신 측이 다른 단위를
 * 쓰면 화면 표기만 바꾸면 된다.
 */
public record LoadSafetyMessage(
        @JsonAlias("forkliftId") String vehicleId,
        String cargoId,
        Double forkHeight,
        Double cargoHeight,
        Double roll,
        Double pitch,
        Double loadOffsetX,
        Double loadOffsetY,
        String riskLevel,
        String riskCode,
        String message,
        String source,
        OffsetDateTime detectedAt
) {
}
