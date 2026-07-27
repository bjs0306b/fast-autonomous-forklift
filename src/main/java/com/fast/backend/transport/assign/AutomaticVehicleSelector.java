package com.fast.backend.transport.assign;

import java.util.List;
import java.util.Optional;

/**
 * 자동 차량 배정 전략(prompt46.md 12장). pickup 위치에서 가장 적합한 가용 차량을 고른다.
 *
 * <p>구현을 인터페이스로 분리한 이유: prompt46.md 12장이 "위치 저장 기능이 불완전하면 자동 배정을 억지로
 * 완성하지 말고 인터페이스와 구현 초안까지만" 두라고 했고, 실제로 차량 위치가 아직 DB에 저장되지 않기
 * 때문이다(prompt44.md). 거리 기준이 확정·활성화되면 이 인터페이스의 새 구현으로 교체·확장한다.
 */
public interface AutomaticVehicleSelector {

    /**
     * 후보 중 배정 가능한 차량을 하나 고른다. 가용 차량이 없으면 {@link Optional#empty()}.
     * (예외 변환은 호출자 책임 — 없을 때 어떤 ErrorCode로 응답할지는 Service가 정한다.)
     *
     * @param pickupX pickup X(m)
     * @param pickupY pickup Y(m)
     * @param candidates 후보 차량
     */
    Optional<VehicleCandidate> select(double pickupX, double pickupY, List<VehicleCandidate> candidates);
}
