package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 차량 현재 상태 저장 흐름 회귀 테스트.
 *
 * <p>과거 이력 기능을 제거하면서 {@code VehicleStatusServiceHistoryTest}에 있던 검증 중
 * <b>이력과 무관한 것</b>(미등록 차량 거부, 알 수 없는 상태 정규화)을 이 파일로 옮겨 보존했다.
 * 이력 전용 검증(이력 INSERT, heartbeat 이력 누적, 이력 실패 롤백)과 배터리 범위 검증은 함께 삭제했다.
 */
class VehicleStatusServiceTest {

    private static final OffsetDateTime MESSAGE_AT =
            OffsetDateTime.of(2026, 8, 3, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleCurrentStatusMapper statusMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private VehicleStatusService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        statusMapper = mock(VehicleCurrentStatusMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new VehicleStatusService(vehicleMapper, statusMapper, broadcaster);
        when(statusMapper.findByVehicleId(any())).thenReturn(Optional.empty());
    }

    @Test
    void registeredVehicle_upsertsCurrentStatusAndBroadcasts() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        VehicleStatusResponse response =
                service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("MOVING", MESSAGE_AT));

        ArgumentCaptor<VehicleCurrentStatus> captor = ArgumentCaptor.forClass(VehicleCurrentStatus.class);
        verify(statusMapper).upsert(captor.capture());
        assertThat(captor.getValue().getVehicleId()).isEqualTo("FORKLIFT-01");
        assertThat(captor.getValue().getStatus()).isEqualTo(VehicleStatus.MOVING);
        assertThat(captor.getValue().getReceivedAt()).isNotNull();

        assertThat(response.status()).isEqualTo(VehicleStatus.MOVING);
        verify(broadcaster).broadcastStatus(eq("FORKLIFT-01"), any(), eq(MESSAGE_AT));
    }

    /** 미등록 차량은 저장도 브로드캐스트도 하지 않는다(기존 정책 유지). */
    @Test
    void unregisteredVehicle_savesNothing() {
        when(vehicleMapper.existsByVehicleId("UNKNOWN-01")).thenReturn(false);

        assertThatThrownBy(() -> service.updateCurrentStatus(
                "UNKNOWN-01", new VehicleStatusUpdateCommand("MOVING", MESSAGE_AT)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);

        verify(statusMapper, never()).upsert(any());
        verify(broadcaster, never()).broadcastStatus(any(), any(), any());
    }

    /** 알 수 없는 상태 문자열은 거부하지 않고 UNKNOWN 으로 정규화한다(기존 정책 유지). */
    @Test
    void unknownStatusString_isNormalizedToUnknown() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("NOT-A-STATUS", MESSAGE_AT));

        ArgumentCaptor<VehicleCurrentStatus> captor = ArgumentCaptor.forClass(VehicleCurrentStatus.class);
        verify(statusMapper).upsert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
    }

    /** messageAt 이 없으면 수신 시각을 브로드캐스트 발생 시각으로 쓴다(기존 정책 유지). */
    @Test
    void missingMessageAt_fallsBackToReceivedAt() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("IDLE", null));

        ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(broadcaster).broadcastStatus(eq("FORKLIFT-01"), any(), captor.capture());
        assertThat(captor.getValue()).isNotNull();
    }
}
