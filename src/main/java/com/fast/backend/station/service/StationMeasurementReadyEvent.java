package com.fast.backend.station.service;

/** MOVE 성공 트랜잭션이 끝난 뒤 측정 요청 발행을 즉시 시도하게 하는 내부 이벤트. */
public record StationMeasurementReadyEvent(Long taskId) {
}
