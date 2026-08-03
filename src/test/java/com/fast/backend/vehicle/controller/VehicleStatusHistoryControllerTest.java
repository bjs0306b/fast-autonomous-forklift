package com.fast.backend.vehicle.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryListResponse;
import com.fast.backend.vehicle.service.VehicleStatusHistoryService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleStatusHistoryControllerTest {

    private static final OffsetDateTime FROM =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime TO =
            OffsetDateTime.of(2026, 8, 4, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    private final VehicleStatusHistoryService service = mock(VehicleStatusHistoryService.class);
    private final VehicleStatusHistoryController controller = new VehicleStatusHistoryController(service);

    @Test
    void forwardsQueryParametersAndWrapsInApiResponse() {
        VehicleStatusHistoryListResponse expected =
                VehicleStatusHistoryListResponse.of("REAL-F01", 0, 20, 42L, List.of());
        when(service.getHistory(eq("REAL-F01"), any(), any(), any(), eq(0), eq(20))).thenReturn(expected);

        ApiResponse<VehicleStatusHistoryListResponse> response =
                controller.statusHistory("REAL-F01", FROM, TO, "MOVING", 0, 20);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(expected);
        verify(service).getHistory("REAL-F01", FROM, TO, "MOVING", 0, 20);
    }

    @Test
    void withoutOptionalParameters_passesNullFilters() {
        when(service.getHistory(any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(VehicleStatusHistoryListResponse.of("REAL-F01", 0, 20, 0L, List.of()));

        controller.statusHistory("REAL-F01", null, null, null, 0, 20);

        verify(service).getHistory("REAL-F01", null, null, null, 0, 20);
    }

    /** 검증 실패는 Controller가 감싸지 않고 GlobalExceptionHandler로 그대로 넘긴다(기존 관례). */
    @Test
    void serviceRejection_isPropagated() {
        when(service.getHistory(any(), any(), any(), any(), any(Integer.class), any(Integer.class)))
                .thenThrow(new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: NOPE-01"));

        assertThatThrownBy(() -> controller.statusHistory("NOPE-01", null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
    }
}
