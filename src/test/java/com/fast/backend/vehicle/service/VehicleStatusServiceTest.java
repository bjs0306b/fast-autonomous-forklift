package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VehicleStatusService.updateCurrentStatus의 처리 순서(차량 존재 확인 → 정규화 → 검증 → 오래된 메시지
 * 판정 → upsert → 브로드캐스트, prompt16.md 11장)를 Mapper/Broadcaster를 모킹해 검증한다.
 * 이 메서드는 REST 테스트 API와 향후 MQTT Adapter가 공유할 유일한 진입점이므로, 호출자를 특정하지
 * 않고 커맨드 객체만으로 테스트한다.
 */
class VehicleStatusServiceTest {

    private VehicleMapper vehicleMapper;
    private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;
    private VehicleStatusHistoryMapper vehicleStatusHistoryMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private VehicleStatusService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        vehicleCurrentStatusMapper = mock(VehicleCurrentStatusMapper.class);
        vehicleStatusHistoryMapper = mock(VehicleStatusHistoryMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new VehicleStatusService(
                vehicleMapper, vehicleCurrentStatusMapper, vehicleStatusHistoryMapper, broadcaster);
    }

    @Test
    void updateCurrentStatus_unregisteredVehicle_throwsAndNeverUpsertsOrBroadcasts() {
        when(vehicleMapper.findByVehicleId("NOPE")).thenReturn(Optional.empty());

        BusinessException exception = catchThrowableOfType(
                () -> service.updateCurrentStatus("NOPE", command("ACTIVE", null, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND);
        verify(vehicleCurrentStatusMapper, never()).upsert(any());
        verify(vehicleStatusHistoryMapper, never()).insert(any());
        verify(broadcaster, never()).broadcastStatus(any(), any(), any());
    }

    @Test
    void updateCurrentStatus_validCommand_upsertsAndBroadcasts() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01",
                new VehicleStatusUpdateCommand("ACTIVE", 82, 1.2, 3.4, 90.0, 0.4, null));

        assertThat(response.status()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(response.battery()).isEqualTo(82);
        verify(vehicleCurrentStatusMapper).upsert(any(VehicleCurrentStatus.class));
        verify(vehicleStatusHistoryMapper).insert(any(VehicleStatusHistory.class));
        verify(broadcaster).broadcastStatus(eq("SIM-F01"), any(VehicleStatusResponse.class), any());

        InOrder order = inOrder(vehicleCurrentStatusMapper, vehicleStatusHistoryMapper, broadcaster);
        order.verify(vehicleCurrentStatusMapper).upsert(any());
        order.verify(vehicleStatusHistoryMapper).insert(any());
        order.verify(broadcaster).broadcastStatus(any(), any(), any());
    }

    @Test
    void updateCurrentStatus_historyInsert_copiesFieldsFromCurrentStatus() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        service.updateCurrentStatus("SIM-F01",
                new VehicleStatusUpdateCommand("ACTIVE", 82, 1.2, 3.4, 90.0, 0.4, null));

        org.mockito.ArgumentCaptor<VehicleStatusHistory> captor =
                org.mockito.ArgumentCaptor.forClass(VehicleStatusHistory.class);
        verify(vehicleStatusHistoryMapper).insert(captor.capture());
        VehicleStatusHistory history = captor.getValue();
        assertThat(history.getVehicleId()).isEqualTo("SIM-F01");
        assertThat(history.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(history.getBattery()).isEqualTo(82);
        assertThat(history.getPositionX()).isEqualTo(1.2);
        assertThat(history.getPositionY()).isEqualTo(3.4);
        assertThat(history.getCreatedAt()).isNotNull();
    }

    @Test
    void updateCurrentStatus_currentStatusUpsertFails_historyInsertNeverCalled() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db error")).when(vehicleCurrentStatusMapper).upsert(any());

        try {
            service.updateCurrentStatus("SIM-F01", command("ACTIVE", null, null));
        } catch (RuntimeException ignored) {
            // 예외 전파 자체는 이 테스트의 관심사가 아니다 — history insert가 호출되지 않았는지만 검증한다.
        }

        verify(vehicleStatusHistoryMapper, never()).insert(any());
        verify(broadcaster, never()).broadcastStatus(any(), any(), any());
    }

    @Test
    void updateCurrentStatus_historyInsertFails_exceptionPropagatesForTransactionRollback() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("history insert failed")).when(vehicleStatusHistoryMapper).insert(any());

        RuntimeException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> service.updateCurrentStatus("SIM-F01", command("ACTIVE", null, null)),
                RuntimeException.class);

        // @Transactional 메서드에서 언체크 예외가 그대로 빠져나가야 Spring이 트랜잭션을 롤백한다 —
        // 여기서 예외를 삼키면 current status upsert만 반영된 부분 성공이 커밋되어 버린다.
        assertThat(exception).hasMessage("history insert failed");
        verify(broadcaster, never()).broadcastStatus(any(), any(), any());
    }

    @Test
    void updateCurrentStatus_unrecognizedStatusString_normalizesToUnknownAndStillUpserts() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        // "LOADING"은 prompt16.md 5장에서 후보로만 거론됐을 뿐 enum에 추가되지 않은 값이다 — 여전히
        // UNKNOWN으로 흡수돼야 한다. "MOVING"은 prompt25.md 1.1장에서 ACTIVE로 정규화하기로 확정돼
        // 더 이상 "인식 불가" 예시로 쓸 수 없다(아래 별도 테스트로 이동).
        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01", command("LOADING", null, null));

        assertThat(response.status()).isEqualTo(VehicleStatus.UNKNOWN);
        verify(vehicleCurrentStatusMapper).upsert(any());
    }

    @Test
    void updateCurrentStatus_movingStatusString_normalizesToActiveAndStillUpserts() {
        // prompt25.md 1.1장: VehicleStatus.fromRaw()가 MOVING을 ACTIVE로 정규화하도록 확정됐다. 이
        // 매핑은 상태 토픽 경로(ForkliftStatusService → 이 메서드)에도 그대로 적용된다 — fromRaw()가
        // 호출자를 구분하지 않는 공용 메서드이기 때문이다.
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01", command("MOVING", null, null));

        assertThat(response.status()).isEqualTo(VehicleStatus.ACTIVE);
        verify(vehicleCurrentStatusMapper).upsert(any());
    }

    @Test
    void updateCurrentStatus_batteryOutOfRange_throwsAndNeverUpserts() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));

        BusinessException exception = catchThrowableOfType(
                () -> service.updateCurrentStatus("SIM-F01", command("ACTIVE", 150, null)),
                BusinessException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_BATTERY_OUT_OF_RANGE);
        verify(vehicleCurrentStatusMapper, never()).upsert(any());
    }

    @Test
    void updateCurrentStatus_missingBatteryAndPosition_stillSucceeds() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01", command("IDLE", null, null));

        assertThat(response.status()).isEqualTo(VehicleStatus.IDLE);
        assertThat(response.battery()).isNull();
        assertThat(response.positionX()).isNull();
        verify(vehicleCurrentStatusMapper).upsert(any());
    }

    @Test
    void updateCurrentStatus_nullMessageAt_usesServerReceivedTimeAndStillUpserts() {
        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.empty());

        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01", command("ACTIVE", null, null));

        assertThat(response.messageAt()).isNotNull();
        assertThat(response.receivedAt()).isNotNull();
    }

    @Test
    void updateCurrentStatus_olderMessageThanExisting_isIgnored() {
        LocalDateTime existingMessageAt = LocalDateTime.now();
        VehicleCurrentStatus existing = new VehicleCurrentStatus();
        existing.setVehicleId("SIM-F01");
        existing.setStatus(VehicleStatus.ACTIVE);
        existing.setBattery(90);
        existing.setMessageAt(existingMessageAt);
        existing.setReceivedAt(existingMessageAt);

        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(existing));

        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01",
                command("ERROR", null, existingMessageAt.minusMinutes(1)));

        // 오래된 메시지는 무시되고 기존 상태(ACTIVE)가 그대로 반환돼야 한다.
        assertThat(response.status()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(response.battery()).isEqualTo(90);
        verify(vehicleCurrentStatusMapper, never()).upsert(any());
        verify(vehicleStatusHistoryMapper, never()).insert(any());
        verify(broadcaster, never()).broadcastStatus(any(), any(), any());
    }

    @Test
    void updateCurrentStatus_newerMessageThanExisting_overwritesAndBroadcasts() {
        LocalDateTime existingMessageAt = LocalDateTime.now().minusMinutes(5);
        VehicleCurrentStatus existing = new VehicleCurrentStatus();
        existing.setVehicleId("SIM-F01");
        existing.setStatus(VehicleStatus.IDLE);
        existing.setMessageAt(existingMessageAt);

        when(vehicleMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(vehicle("SIM-F01", VehicleSource.SIMULATION)));
        when(vehicleCurrentStatusMapper.findByVehicleId("SIM-F01")).thenReturn(Optional.of(existing));

        VehicleStatusResponse response = service.updateCurrentStatus("SIM-F01",
                command("ACTIVE", null, existingMessageAt.plusMinutes(1)));

        assertThat(response.status()).isEqualTo(VehicleStatus.ACTIVE);
        verify(vehicleCurrentStatusMapper).upsert(any());
        verify(vehicleStatusHistoryMapper).insert(any());
        verify(broadcaster).broadcastStatus(any(), any(), any());
    }

    private Vehicle vehicle(String vehicleId, VehicleSource source) {
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId + " 이름");
        vehicle.setSource(source);
        vehicle.setActive(true);
        return vehicle;
    }

    private VehicleStatusUpdateCommand command(String status, Integer battery, LocalDateTime messageAt) {
        return new VehicleStatusUpdateCommand(status, battery, null, null, null, null, messageAt);
    }
}
