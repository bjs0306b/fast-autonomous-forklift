package com.fast.backend.traffic.service;

import com.fast.backend.traffic.domain.VehicleMotion;
import com.fast.backend.traffic.domain.WorkZone;

import java.util.Collection;
import java.util.List;

/**
 * 선반 작업 구역의 점유 상태를 알려주는 쪽 (FR-502-1a).
 *
 * <p>{@code TrafficControlService} 가 구현체({@link WorkZoneRegistry})가 아니라 이 인터페이스에
 * 의존한다 — 판단 로직이 "구역을 어디서 어떻게 읽어 오는지"(DB·메모리·설정)와 분리되고, 테스트에서
 * 구역 상태를 직접 세울 수 있다.
 */
public interface WorkZoneProvider {

    /** 차량 상태를 보고 점유를 다시 계산한다. */
    void refresh(Collection<VehicleMotion> motions);

    /** 현재 구역 목록(점유 상태 포함). */
    List<WorkZone> zones();
}
