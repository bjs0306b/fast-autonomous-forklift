package com.fast.backend.transport.dto;

/** 입고 바이에서 시뮬레이터가 생성·적재할 화물 정보. 높이는 AI 측정값(m)이다. */
public record IsaacCargoLoadMessage(double height, String cargoId) {
}
