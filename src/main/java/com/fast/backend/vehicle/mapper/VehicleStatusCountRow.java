package com.fast.backend.vehicle.mapper;

/**
 * 상태별 차량 수 집계 SQL(GROUP BY) 결과 한 행. status는 DB에 저장된 원시 문자열 그대로(항상
 * VehicleStatus enum 값 중 하나)이며, Service가 이 값을 enum으로 안전 변환한다.
 */
public class VehicleStatusCountRow {

    private String status;
    private long count;

    public VehicleStatusCountRow() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
