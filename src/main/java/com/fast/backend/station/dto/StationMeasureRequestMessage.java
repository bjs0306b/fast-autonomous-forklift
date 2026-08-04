package com.fast.backend.station.dto;

/** 백엔드가 측정 프로그램에 발행하는 화물 측정 요청. */
public record StationMeasureRequestMessage(
        Long cargoId
) {
}
