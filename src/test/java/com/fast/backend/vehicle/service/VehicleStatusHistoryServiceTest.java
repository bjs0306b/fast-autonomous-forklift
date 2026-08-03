package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryListResponse;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 차량 상태 이력 조회 서비스(Jira -133)의 검증·페이징 계산을 확인한다. */
class VehicleStatusHistoryServiceTest {

    private static final OffsetDateTime FROM =
            OffsetDateTime.of(2026, 8, 1, 0, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime TO =
            OffsetDateTime.of(2026, 8, 4, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleStatusHistoryMapper historyMapper;
    private VehicleStatusHistoryService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        historyMapper = mock(VehicleStatusHistoryMapper.class);
        service = new VehicleStatusHistoryService(vehicleMapper, historyMapper);
        when(vehicleMapper.existsByVehicleId("REAL-F01")).thenReturn(true);
    }

    @Test
    void defaultPaging_returnsItemsAndPageMeta() {
        when(historyMapper.countByVehicleId(eq("REAL-F01"), isNull(), isNull(), isNull())).thenReturn(42L);
        when(historyMapper.findByVehicleId(eq("REAL-F01"), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(history(42L, VehicleStatus.MOVING, 87)));

        VehicleStatusHistoryListResponse response =
                service.getHistory("REAL-F01", null, null, null, 0, 20);

        assertThat(response.vehicleId()).isEqualTo("REAL-F01");
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(42L);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).historyId()).isEqualTo(42L);
        assertThat(response.items().get(0).status()).isEqualTo(VehicleStatus.MOVING);
        // 통신 규격대로 +09:00을 붙여 내보낸다.
        assertThat(response.items().get(0).messageAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void secondPage_passesLimitAndOffsetToMapper() {
        when(historyMapper.countByVehicleId(any(), any(), any(), any())).thenReturn(42L);
        when(historyMapper.findByVehicleId(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        service.getHistory("REAL-F01", null, null, null, 2, 20);

        verify(historyMapper).findByVehicleId("REAL-F01", null, null, null, 20, 40);
    }

    @Test
    void rangeFilter_convertsOffsetTimesToLocalWallClock() {
        when(historyMapper.countByVehicleId(any(), any(), any(), any())).thenReturn(0L);
        when(historyMapper.findByVehicleId(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        service.getHistory("REAL-F01", FROM, TO, "MOVING", 0, 20);

        verify(historyMapper).findByVehicleId(
                "REAL-F01",
                LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 4, 0, 0),
                VehicleStatus.MOVING, 20, 0);
    }

    @Test
    void emptyHistory_returnsEmptyItemsWithValidPageMeta() {
        when(historyMapper.countByVehicleId(any(), any(), any(), any())).thenReturn(0L);
        when(historyMapper.findByVehicleId(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());

        VehicleStatusHistoryListResponse response = service.getHistory("REAL-F01", null, null, null, 0, 20);

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Nested
    class Rejections {

        @Test
        void unknownVehicle_throwsVehicleNotFound() {
            when(vehicleMapper.existsByVehicleId("NOPE-01")).thenReturn(false);

            assertThatThrownBy(() -> service.getHistory("NOPE-01", null, null, null, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        }

        @Test
        void negativePage_isRejected() {
            assertThatThrownBy(() -> service.getHistory("REAL-F01", null, null, null, -1, 20))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("page");
        }

        @Test
        void sizeOutOfRange_isRejected() {
            assertThatThrownBy(() -> service.getHistory("REAL-F01", null, null, null, 0, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("size");
            assertThatThrownBy(() -> service.getHistory("REAL-F01", null, null, null, 0, 101))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("size");
        }

        @Test
        void fromNotBeforeTo_isRejected() {
            assertThatThrownBy(() -> service.getHistory("REAL-F01", TO, FROM, null, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThatThrownBy(() -> service.getHistory("REAL-F01", FROM, FROM, null, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("from");
        }

        @Test
        void unsupportedStatus_isRejectedInsteadOfFallingBackToUnknown() {
            assertThatThrownBy(() -> service.getHistory("REAL-F01", null, null, "NOT-A-STATUS", 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("지원하지 않는 status");
        }
    }

    private VehicleStatusHistory history(Long historyId, VehicleStatus status, Integer battery) {
        VehicleStatusHistory history = new VehicleStatusHistory();
        history.setHistoryId(historyId);
        history.setVehicleId("REAL-F01");
        history.setStatus(status);
        history.setBattery(battery);
        history.setMessageAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        history.setReceivedAt(LocalDateTime.of(2026, 8, 3, 9, 0, 0, 120_000_000));
        return history;
    }
}
