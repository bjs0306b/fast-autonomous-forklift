package com.fast.backend.loadsafety.domain;

/**
 * 적재 화물 안전 위험 단계(prompt63.md 3장·5장).
 *
 * <p><b>백엔드는 이 값을 계산하지 않는다.</b> 비전·센서 노드가 판정해 보낸 {@code riskLevel}을 그대로
 * 보존해 REST/WebSocket으로 중계할 뿐이다. 프론트도 마찬가지로 재계산하지 않는다(3장 "프론트는
 * 위험도를 계산하지 않는다"). 임계값·판정 알고리즘은 이번 범위에서 제외됐다.
 *
 * <p><b>단계 사다리는 계약 가정이다</b>: prompt63.md는 예시로 {@code "WARNING"} 하나만 제시했고 전체
 * 값 집합을 명시하지 않았다(§5 이후가 잘려 있다). 이 프로젝트의 다른 상태 enum과 동일한 방침으로
 * NORMAL &lt; CAUTION &lt; WARNING &lt; DANGER 4단계를 정의하고, <b>모르는 값은 다른 단계로 흡수하지 않고</b>
 * {@link #UNKNOWN}으로 떨어뜨린다 — 안전 도메인에서 "모르는 값을 적당히 해석"하는 것은 금지다
 * ({@code VehicleCommandType}·{@link com.fast.backend.vehicle.domain.VehicleStatus}와 같은 원칙).
 *
 * <p>비전 팀이 다른 값 집합을 쓴다면 이 enum에 값을 추가해야 한다. 그때까지 미정의 값은 UNKNOWN으로
 * 수신되며 <b>메시지 자체는 버리지 않는다</b>(높이·기울기 등 나머지 측정값은 여전히 유효하기 때문).
 */
public enum LoadSafetyRiskLevel {

    /** 이상 없음. */
    NORMAL,
    /** 주의 — 임계값에 근접. */
    CAUTION,
    /** 경고 — 임계값 초과, 조작자 개입 권고. */
    WARNING,
    /** 위험 — 즉시 조치 필요. */
    DANGER,
    /** 판정 불가 또는 이 백엔드가 모르는 값. */
    UNKNOWN;

    /**
     * 외부(MQTT) 원시 문자열을 안전하게 매핑한다. null·빈 문자열·미정의 값은 모두 {@link #UNKNOWN}이며
     * 예외를 던지지 않는다 — 잘못된 riskLevel 하나 때문에 적재 안전 메시지 전체가 유실되면 안 된다.
     */
    public static LoadSafetyRiskLevel fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return LoadSafetyRiskLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** 화면에서 경고 오버레이를 띄워야 하는 단계인지(WARNING 이상). */
    public boolean isAlerting() {
        return this == WARNING || this == DANGER;
    }
}
