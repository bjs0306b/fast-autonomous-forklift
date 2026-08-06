package com.fast.backend.traffic.domain;

/**
 * 선반 작업 구역 — 선행 차량이 하역하는 동안 후행 차량의 진입을 막는 원형 구역.
 *
 * <p>중심은 {@code storage_slot} 의 접근 좌표({@code destination_x/y})다. 사각형이 아니라 원으로
 * 두는 이유는 선반 진입 방향이 차량마다 다를 수 있고, 반경 하나만 조정하면 되기 때문이다.
 *
 * @param occupiedBy 이 구역을 점유한 차량 ID. {@code null} 이면 비어 있다
 */
public record WorkZone(String slotCode, double x, double y, double radiusM, String occupiedBy) {

    public boolean isOccupied() {
        return occupiedBy != null;
    }

    /** 이 차량이 점유자인가. 점유자 본인은 자기 구역 때문에 정지하지 않는다. */
    public boolean isOccupiedBy(String vehicleId) {
        return occupiedBy != null && occupiedBy.equals(vehicleId);
    }

    public WorkZone occupy(String vehicleId) {
        return new WorkZone(slotCode, x, y, radiusM, vehicleId);
    }

    public WorkZone release() {
        return new WorkZone(slotCode, x, y, radiusM, null);
    }

    /** 주어진 좌표가 이 구역 안인가. */
    public boolean contains(double px, double py) {
        double dx = px - x;
        double dy = py - y;
        return Math.hypot(dx, dy) <= radiusM;
    }
}
