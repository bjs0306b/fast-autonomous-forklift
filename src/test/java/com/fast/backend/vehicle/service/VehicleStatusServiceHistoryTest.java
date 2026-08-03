package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 상태 메시지 처리 시 최신 상태와 이력이 함께 저장되는지 검증한다(Jira -132). */
class VehicleStatusServiceHistoryTest {

    private static final OffsetDateTime MESSAGE_AT =
            OffsetDateTime.of(2026, 8, 3, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleCurrentStatusMapper statusMapper;
    private VehicleStatusHistoryMapper historyMapper;
    private VehicleStatusService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        statusMapper = mock(VehicleCurrentStatusMapper.class);
        historyMapper = mock(VehicleStatusHistoryMapper.class);
        service = new VehicleStatusService(
                vehicleMapper, statusMapper, historyMapper, mock(VehicleWebSocketBroadcaster.class));
        when(statusMapper.findByVehicleId(any())).thenReturn(Optional.empty());
    }

    @Test
    void registeredVehicle_savesCurrentStatusAndHistory() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("MOVING", 87, MESSAGE_AT));

        verify(statusMapper).upsert(any());
        ArgumentCaptor<VehicleStatusHistory> captor = ArgumentCaptor.forClass(VehicleStatusHistory.class);
        verify(historyMapper).insert(captor.capture());
        VehicleStatusHistory saved = captor.getValue();
        assertThat(saved.getVehicleId()).isEqualTo("FORKLIFT-01");
        assertThat(saved.getStatus()).isEqualTo(VehicleStatus.MOVING);
        assertThat(saved.getBattery()).isEqualTo(87);
        assertThat(saved.getMessageAt()).isEqualTo(MESSAGE_AT.toLocalDateTime());
        assertThat(saved.getReceivedAt()).isNotNull();
    }

    @Test
    void unregisteredVehicle_savesNeitherStatusNorHistory() {
        when(vehicleMapper.existsByVehicleId("UNKNOWN-01")).thenReturn(false);

        assertThatThrownBy(() -> service.updateCurrentStatus(
                "UNKNOWN-01", new VehicleStatusUpdateCommand("MOVING", 87, MESSAGE_AT)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);

        verify(statusMapper, never()).upsert(any());
        verify(historyMapper, never()).insert(any());
    }

    @Test
    void invalidBattery_savesNeitherStatusNorHistory() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        assertThatThrownBy(() -> service.updateCurrentStatus(
                "FORKLIFT-01", new VehicleStatusUpdateCommand("MOVING", 120, MESSAGE_AT)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VEHICLE_BATTERY_OUT_OF_RANGE);

        verify(statusMapper, never()).upsert(any());
        verify(historyMapper, never()).insert(any());
    }

    /** 상태가 바뀌지 않는 heartbeat도 매번 이력으로 남긴다. */
    @Test
    void repeatedSameStatusHeartbeat_savesEveryHistoryRow() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("IDLE", 100, MESSAGE_AT));
        service.updateCurrentStatus(
                "FORKLIFT-01", new VehicleStatusUpdateCommand("IDLE", 100, MESSAGE_AT.plusSeconds(1)));
        service.updateCurrentStatus(
                "FORKLIFT-01", new VehicleStatusUpdateCommand("IDLE", 100, MESSAGE_AT.plusSeconds(2)));

        verify(historyMapper, times(3)).insert(any());
    }

    /** 알 수 없는 상태 문자열은 기존 규칙대로 UNKNOWN으로 정규화해 저장한다(메시지를 버리지 않는다). */
    @Test
    void unknownStatusString_isNormalizedInHistory() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("NOT-A-STATUS", 50, MESSAGE_AT));

        ArgumentCaptor<VehicleStatusHistory> captor = ArgumentCaptor.forClass(VehicleStatusHistory.class);
        verify(historyMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
    }

    /** messageAt이 없는 상태 갱신(테스트 API 경로)도 이력은 남고 message_at만 null이다. */
    @Test
    void missingMessageAt_savesHistoryWithNullMessageAt() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);

        service.updateCurrentStatus("FORKLIFT-01", new VehicleStatusUpdateCommand("IDLE", 100, null));

        ArgumentCaptor<VehicleStatusHistory> captor = ArgumentCaptor.forClass(VehicleStatusHistory.class);
        verify(historyMapper).insert(captor.capture());
        assertThat(captor.getValue().getMessageAt()).isNull();
        assertThat(captor.getValue().getReceivedAt()).isNotNull();
    }

    /** 이력 INSERT가 실패하면 예외가 밖으로 나가 트랜잭션이 롤백된다(최신 상태만 남는 부분 성공 금지). */
    @Test
    void historyInsertFailure_propagatesInsteadOfPartialSuccess() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("insert failed"))
                .when(historyMapper).insert(any());

        assertThatThrownBy(() -> service.updateCurrentStatus(
                "FORKLIFT-01", new VehicleStatusUpdateCommand("MOVING", 87, MESSAGE_AT)))
                .isInstanceOf(IllegalStateException.class);
    }
}
