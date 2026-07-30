package com.fast.backend.storage.domain;

import java.time.LocalDateTime;

/**
 * {@code cargo} 한 행 — FR-202 최종 스키마(prompt85)에서 <b>식별자와 등록 시각만</b> 남았다.
 *
 * <p>치수(width/length/height/volume)와 대리키 id 가 사라졌다. 화물 높이는 이제
 * {@code station_measurement.cargo_height}(측정 결과)에서만 온다 — <b>폭·길이·부피는 어디에도
 * 저장되지 않는다.</b> 그래서 적재 위치 추천이 평면 적합성을 볼 수 없다
 * ({@link com.fast.backend.storage.placement.PlacementService} Javadoc 참고).
 */
public class Cargo {

    private String cargoId;
    private LocalDateTime createdAt;

    public String getCargoId() {
        return cargoId;
    }

    public void setCargoId(String cargoId) {
        this.cargoId = cargoId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
