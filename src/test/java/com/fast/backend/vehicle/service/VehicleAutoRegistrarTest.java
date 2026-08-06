package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.config.VehicleProperties;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MQTT 로 처음 관측된 차량이 등록되는지 — 차량 수만큼 화면에 뜨게 하는 유일한 경로다. */
class VehicleAutoRegistrarTest {

    private final VehicleMapper vehicleMapper = mock(VehicleMapper.class);
    private final VehicleCurrentStatusMapper statusMapper = mock(VehicleCurrentStatusMapper.class);

    private VehicleAutoRegistrar registrar(boolean enabled) {
        return new VehicleAutoRegistrar(
                vehicleMapper, statusMapper, new VehicleProperties(enabled, false, 10L, Map.of()));
    }

    @Test
    @DisplayName("1. 처음 보는 차량은 받은 ID 그대로 등록된다 — 이름을 지어내거나 ID 를 바꾸지 않는다")
    void registersUnknownVehicleWithReceivedId() {
        when(vehicleMapper.existsByVehicleId("SIM-F02")).thenReturn(false);

        assertThat(registrar(true).ensureRegistered("SIM-F02", "mqtt-location")).isTrue();

        ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
        verify(vehicleMapper).insert(captor.capture());
        assertThat(captor.getValue().getVehicleId()).isEqualTo("SIM-F02");
        assertThat(captor.getValue().isActive()).isTrue();

        ArgumentCaptor<VehicleCurrentStatus> status = ArgumentCaptor.forClass(VehicleCurrentStatus.class);
        verify(statusMapper).upsert(status.capture());
        assertThat(status.getValue().getStatus()).isEqualTo(VehicleStatus.UNKNOWN);
    }

    @Test
    @DisplayName("2. 이미 등록된 차량은 다시 넣지 않는다")
    void doesNotReinsertKnownVehicle() {
        when(vehicleMapper.existsByVehicleId("SIM-F01")).thenReturn(true);

        assertThat(registrar(true).ensureRegistered("SIM-F01", "mqtt-location")).isTrue();
        verify(vehicleMapper, never()).insert(any());
    }

    @Test
    @DisplayName("3. 자동 등록이 꺼져 있으면 등록하지 않고 false — 기존 폐기 동작 유지")
    void skipsWhenDisabled() {
        when(vehicleMapper.existsByVehicleId("SIM-F02")).thenReturn(false);

        assertThat(registrar(false).ensureRegistered("SIM-F02", "mqtt-location")).isFalse();
        verify(vehicleMapper, never()).insert(any());
    }

    @Test
    @DisplayName("4. 첫 메시지가 동시에 두 건 와도 실패가 아니다 — 먼저 넣은 쪽을 인정한다")
    void treatsConcurrentInsertAsSuccess() {
        when(vehicleMapper.existsByVehicleId("SIM-F02")).thenReturn(false);
        when(vehicleMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertThat(registrar(true).ensureRegistered("SIM-F02", "mqtt-location")).isTrue();
        verify(statusMapper, never()).upsert(any());
    }

    @Test
    @DisplayName("5. 빈 vehicleId 는 등록하지 않는다")
    void rejectsBlankVehicleId() {
        assertThat(registrar(true).ensureRegistered("  ", "mqtt-location")).isFalse();
        verify(vehicleMapper, never()).insert(any());
    }
}
