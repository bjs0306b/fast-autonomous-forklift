package com.fast.backend.vehicle.location;

import java.util.List;
import java.util.Optional;

/**
 * 차량별 최신 위치 1건만 유지하는 공급자(prompt47.md 3-3). 자동 배정 준비용 구조로,
 * <b>전체 위치 이력은 저장하지 않는다</b>(DB 저장 금지, 과거 프레임 누적 금지, 재시작 시 소실 허용).
 *
 * <p>기존 WebSocket 전송 구조와 프론트 계약은 그대로 두고, 위치 수신 시 이 공급자의 {@link #update}만
 * 추가로 호출하면 {@code NearestIdleVehicleSelector}가 최신 위치를 소비할 수 있다(이번 슬라이스에서는
 * 자동 배정 API를 제공하지 않으므로 공급 구조까지만 둔다).
 */
public interface LatestVehicleLocationProvider {

    Optional<VehicleLocationSnapshot> findLatest(String vehicleId);

    List<VehicleLocationSnapshot> findAllLatest();

    /** 위치 수신 시 vehicleId별 최신값을 덮어쓴다. */
    /**
     * 최신 위치를 갱신한다.
     *
     * @return 실제로 반영됐으면 true, 더 오래됐거나 같은 시각이라 무시했으면 false.
     *         FR-202 스키마에서 {@code vehicle_current_status.message_at} 이 사라져(prompt85)
     *         "오래된 위치 메시지 무시" 판정을 이 구현이 단독으로 맡는다 — 호출자는 이 반환값으로
     *         DB 반영 여부를 정한다.
     */
    boolean update(VehicleLocationSnapshot snapshot);
}
