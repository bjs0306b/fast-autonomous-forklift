package com.fast.backend.vehicle.dto;

import java.util.List;

/**
 * 차량 상태 이력 목록 응답. 프로젝트에 공통 페이지네이션 프레임워크가 없어
 * ({@link com.fast.backend.transport.dto.TransportTaskListResponse}와 같은 상황) 기존 목록 응답의
 * {@code items/page/size/totalElements} 형태를 그대로 따르고, 조회 대상을 명확히 하기 위한
 * {@code vehicleId}와 클라이언트 페이지 계산용 {@code totalPages}만 더한다.
 */
public record VehicleStatusHistoryListResponse(
        String vehicleId,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<VehicleStatusHistoryResponse> items
) {
    public static VehicleStatusHistoryListResponse of(
            String vehicleId, int page, int size, long totalElements,
            List<VehicleStatusHistoryResponse> items) {
        int totalPages = size <= 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new VehicleStatusHistoryListResponse(
                vehicleId, page, size, totalElements, totalPages, items);
    }
}
