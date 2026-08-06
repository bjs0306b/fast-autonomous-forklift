package com.fast.backend.traffic.domain;

/**
 * "이 차량을 왜 세우는가"를 구조화한 판단 결과 (FR-502-1a).
 *
 * <p>예전에는 사유를 사람이 읽는 문자열 하나로 만들었는데, 그러면 DB 에 남겨도 조회·집계가 안 된다.
 * 그래서 <b>코드</b>({@link #reason})와 <b>근거 값</b>(상대 차량·구역·거리)을 따로 들고 다니고,
 * 사람이 읽는 문장은 {@link #detail()} 이 그 값들로부터 만든다.
 *
 * @param counterpartVehicleId 안전거리 위반 상대 차량. 구역 사유면 그 구역을 점유한 차량
 * @param slotCode             작업 구역 사유일 때의 선반 코드. 안전거리 사유면 {@code null}
 * @param distanceM            판단 당시 유효 거리(m). 구역 사유면 {@code null}
 */
public record TrafficStopDecision(
        TrafficHoldReason reason,
        String counterpartVehicleId,
        String slotCode,
        Double distanceM
) {

    public static TrafficStopDecision safetyDistance(String counterpartVehicleId, double distanceM) {
        return new TrafficStopDecision(
                TrafficHoldReason.SAFETY_DISTANCE, counterpartVehicleId, null, distanceM);
    }

    public static TrafficStopDecision workZone(String slotCode, String occupiedBy) {
        return new TrafficStopDecision(
                TrafficHoldReason.WORK_ZONE_OCCUPIED, occupiedBy, slotCode, null);
    }

    /** 사람이 읽는 사유 문장(로그·DB 상세 컬럼용). */
    public String detail() {
        return switch (reason) {
            case SAFETY_DISTANCE -> "차량 %s 와 거리 %.2fm".formatted(counterpartVehicleId, distanceM);
            case WORK_ZONE_OCCUPIED ->
                    "작업 구역 %s 를 차량 %s 가 점유".formatted(slotCode, counterpartVehicleId);
        };
    }

    /**
     * 같은 사유인가. <b>정지 사유가 바뀌면 이벤트를 다시 남기기 위해</b> 쓴다 —
     * 예를 들어 안전거리 때문에 섰다가 그대로 작업 구역 사유로 바뀌면, 그것은 기록할 가치가 있는
     * 상태 변화다. 반면 같은 사유가 계속되는 동안은 매 tick 기록하지 않는다.
     */
    public boolean sameAs(TrafficStopDecision other) {
        if (other == null) {
            return false;
        }
        return reason == other.reason
                && java.util.Objects.equals(counterpartVehicleId, other.counterpartVehicleId)
                && java.util.Objects.equals(slotCode, other.slotCode);
    }
}
