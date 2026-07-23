package com.fast.backend.isaac.service;

import com.fast.backend.isaac.dto.IsaacForkliftLocationMessage;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.IsaacVehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Isaac Sim 위치 메시지 → {@link IsaacVehicleLocationEventData} 변환·브로드캐스트, 값 검증, 미등록 차량
 * 방어를 검증한다(prompt28.md 3장·15장). DB에는 전혀 쓰지 않는다(존재 확인만 하고, 저장은 하지 않음).
 */
class IsaacForkliftLocationServiceTest {

    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2026, 7, 22, 10, 30, 0, 123_000_000);

    private VehicleMapper vehicleMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private IsaacForkliftLocationService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new IsaacForkliftLocationService(vehicleMapper, broadcaster);
    }

    @Test
    void handleLocation_registeredVehicle_broadcastsWithDirectionInRadiansUnchanged() {
        when(vehicleMapper.existsByVehicleId("SIM01")).thenReturn(true);
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM01", 1.2340, 0.8720, 1.5708, 0.1500, TIMESTAMP);

        service.handleLocation(message);

        ArgumentCaptor<IsaacVehicleLocationEventData> captor =
                ArgumentCaptor.forClass(IsaacVehicleLocationEventData.class);
        verify(broadcaster).broadcastIsaacLocation(eq("SIM01"), captor.capture(), eq(TIMESTAMP));
        IsaacVehicleLocationEventData data = captor.getValue();
        assertThat(data.forkliftId()).isEqualTo("SIM01");
        assertThat(data.x()).isEqualTo(1.2340);
        assertThat(data.y()).isEqualTo(0.8720);
        // rad 값을 degree로 정규화·변환하지 않는다 — 합의 규격 그대로 보존.
        assertThat(data.direction()).isEqualTo(1.5708);
        assertThat(data.speed()).isEqualTo(0.1500);
        assertThat(data.receivedAt()).isNotNull();
    }

    @Test
    void handleLocation_unregisteredVehicle_skipsBroadcast() {
        when(vehicleMapper.existsByVehicleId("SIM99")).thenReturn(false);
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM99", 1.0, 1.0, 0.0, 0.0, TIMESTAMP);

        service.handleLocation(message);

        verify(broadcaster, never()).broadcastIsaacLocation(any(), any(), any());
    }

    @Test
    void handleLocation_mapperThrows_exceptionDoesNotPropagateAndSkipsBroadcast() {
        when(vehicleMapper.existsByVehicleId("SIM01")).thenThrow(new RuntimeException("DB down"));
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM01", 1.0, 1.0, 0.0, 0.0, TIMESTAMP);

        assertThatCode(() -> service.handleLocation(message)).doesNotThrowAnyException();
        verify(broadcaster, never()).broadcastIsaacLocation(any(), any(), any());
    }

    @Test
    void handleLocation_blankForkliftId_skipsWithoutCallingMapper() {
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(" ", 1.0, 1.0, 0.0, 0.0, TIMESTAMP);

        service.handleLocation(message);

        org.mockito.Mockito.verifyNoInteractions(vehicleMapper);
        verify(broadcaster, never()).broadcastIsaacLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullTimestamp_skipsBroadcast() {
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage("SIM01", 1.0, 1.0, 0.0, 0.0, null);

        service.handleLocation(message);

        org.mockito.Mockito.verifyNoInteractions(vehicleMapper);
    }

    @Test
    void handleLocation_nanX_rejectsMessage() {
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM01", Double.NaN, 1.0, 0.0, 0.0, TIMESTAMP);

        service.handleLocation(message);

        verify(broadcaster, never()).broadcastIsaacLocation(any(), any(), any());
    }

    @Test
    void handleLocation_infiniteDirection_rejectsMessage() {
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM01", 1.0, 1.0, Double.POSITIVE_INFINITY, 0.0, TIMESTAMP);

        service.handleLocation(message);

        verify(broadcaster, never()).broadcastIsaacLocation(any(), any(), any());
    }

    @Test
    void handleLocation_negativeSpeed_rejectsMessage() {
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM01", 1.0, 1.0, 0.0, -0.1, TIMESTAMP);

        service.handleLocation(message);

        verify(broadcaster, never()).broadcastIsaacLocation(any(), any(), any());
    }

    @Test
    void handleLocation_negativeDirection_isAcceptedAsIs() {
        // -π~π 범위는 "원칙적으로"라는 표현으로만 명시돼 있어(강제 아님) 음수 자체는 거부하지 않는다.
        when(vehicleMapper.existsByVehicleId("SIM01")).thenReturn(true);
        IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                "SIM01", 1.0, 1.0, -1.5708, 0.0, TIMESTAMP);

        service.handleLocation(message);

        ArgumentCaptor<IsaacVehicleLocationEventData> captor =
                ArgumentCaptor.forClass(IsaacVehicleLocationEventData.class);
        verify(broadcaster).broadcastIsaacLocation(eq("SIM01"), captor.capture(), any());
        assertThat(captor.getValue().direction()).isEqualTo(-1.5708);
    }

    /**
     * 10Hz 연속 수신을 흉내낸다(prompt28.md 3장·15장 "10개의 연속 메시지가 모두 처리됨" — 실제 1초를
     * 기다리는 시간 간격 테스트가 아니라 Thread.sleep 없는 10회 연속 호출이라는 점을 명시한다).
     */
    @Test
    void handleLocation_tenConsecutiveMessages_allBroadcastWithoutDbWrite() {
        when(vehicleMapper.existsByVehicleId("SIM01")).thenReturn(true);

        for (int i = 0; i < 10; i++) {
            IsaacForkliftLocationMessage message = new IsaacForkliftLocationMessage(
                    "SIM01", 1.0 + i * 0.1, 1.0, 0.0, 0.1, TIMESTAMP.plusNanos(i * 100_000_000L));
            service.handleLocation(message);
        }

        verify(broadcaster, times(10)).broadcastIsaacLocation(eq("SIM01"), any(), any());
        verify(vehicleMapper, times(10)).existsByVehicleId("SIM01");
        // 위치 메시지는 DB에 저장하지 않는다 — existsByVehicleId 외 다른 Mapper 메서드가 없다(생성자 의존성 자체가 그렇게 구성됨).
    }
}
