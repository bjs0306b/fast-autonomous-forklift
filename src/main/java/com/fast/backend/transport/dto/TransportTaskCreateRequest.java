package com.fast.backend.transport.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/transport-tasks 요청 — FR-202 최종 스키마(prompt85).
 *
 * <p>옛 요청은 {@code palletId} 로 픽업 좌표를 얻었다. 파렛트 테이블이 사라졌고
 * {@code transport_task.source_x/y/heading} 은 NOT NULL 이므로 <b>호출자가 픽업 좌표를 직접 준다</b>.
 * {@code measurementId} 도 필수다 — 최종 스키마에서 작업은 "어떤 측정 결과로 판단했는지"를 FK 로 갖는다.
 */
public record TransportTaskCreateRequest(
        @NotBlank String cargoId,
        @NotBlank String measurementId,
        @NotNull Double sourceX,
        @NotNull Double sourceY,
        @NotNull Double sourceHeading
) {
}
