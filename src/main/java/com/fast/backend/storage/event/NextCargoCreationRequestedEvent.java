package com.fast.backend.storage.event;

/** 정상 측정을 마친 화물이 측정 위치를 비운 뒤 다음 입하를 준비하라는 내부 이벤트. */
public record NextCargoCreationRequestedEvent(
        Long previousCargoId,
        String measurementId
) {
}
