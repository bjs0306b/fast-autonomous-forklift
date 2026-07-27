package com.fast.backend.vehicle.dto;

import java.util.List;

/**
 * GET /api/vehicles/status-counts 응답.
 *
 * <p>Map보다 배열({@code items})을 선택했다 — React에서 {@code items.map(...)}으로 바로 렌더링하기
 * 쉽고, 상태 enum 순서를 서버가 고정해서 내려줄 수 있다(Map은 JSON 직렬화 시 키 순서가 라이브러리/버전에
 * 따라 흔들릴 수 있음). 이유는 {@code answer15.md} 11장에서 상세히 설명한다.
 */
public record VehicleStatusCountResponse(
        long total,
        List<StatusCount> items
) {

    public record StatusCount(String status, long count) {
    }
}
