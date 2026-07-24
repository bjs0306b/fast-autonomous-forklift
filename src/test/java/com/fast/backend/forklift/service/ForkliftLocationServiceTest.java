package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ROS2 위치 메시지 → {@link VehicleLocationEventData} 변환과 브로드캐스트 위임, 값 검증, 미등록 차량
 * 방어를 검증한다(prompt24.md 5장·8장·11장, prompt25.md 4장·6장·8장 — ACTIVE 통일·5Hz 대응 보강).
 * 이 클래스는 vehicle_current_status를 갱신하지 않으므로(이유는 VehicleLocationEventData Javadoc 참고)
 * DB 관련 검증은 "호출하지 않음"만 확인한다.
 */
class ForkliftLocationServiceTest {

    private static final OffsetDateTime MESSAGE_AT = LocalDateTime.of(2026, 7, 22, 13, 30, 0).atOffset(java.time.ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private ForkliftLocationService forkliftLocationService;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        forkliftLocationService = new ForkliftLocationService(vehicleMapper, broadcaster);
    }

    @Test
    void handleLocation_registeredVehicle_broadcastsConvertedLocationData() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = fullMessage("FORKLIFT-01");

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), eq(MESSAGE_AT));
        VehicleLocationEventData data = captor.getValue();
        assertThat(data.vehicleId()).isEqualTo("FORKLIFT-01");
        assertThat(data.status()).isEqualTo(VehicleStatus.MOVING); // MOVING 보존(prompt32.md 1장 3번)
        assertThat(data.position().x()).isEqualTo(2.5);
        assertThat(data.position().y()).isEqualTo(4.1);
        assertThat(data.position().frameId()).isEqualTo("map");
        assertThat(data.heading()).isEqualTo(90.0);
        assertThat(data.quaternion().z()).isEqualTo(0.7071);
        assertThat(data.quaternion().w()).isEqualTo(0.7071);
        assertThat(data.speed()).isEqualTo(0.4);
        assertThat(data.messageAt()).isEqualTo(MESSAGE_AT);
        assertThat(data.receivedAt()).isNotNull();
    }

    @Test
    void handleLocation_unregisteredVehicle_skipsBroadcast() {
        when(vehicleMapper.existsByVehicleId("NO-SUCH")).thenReturn(false);
        ForkliftLocationMessage message = fullMessage("NO-SUCH");

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_mapperThrows_exceptionDoesNotPropagateAndSkipsBroadcast() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenThrow(new RuntimeException("DB down"));
        ForkliftLocationMessage message = fullMessage("FORKLIFT-01");

        assertThatCode(() -> forkliftLocationService.handleLocation(message)).doesNotThrowAnyException();
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullVehicleId_skipsWithoutCallingMapper() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                null, null, position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verifyNoInteractions(vehicleMapper);
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_blankVehicleId_skipsWithoutCallingMapper() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "  ", null, position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verifyNoInteractions(vehicleMapper);
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullPosition_skipsBroadcast() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, null, null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullPositionX_skipsBroadcast() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(null, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullPositionY_skipsBroadcast() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, null), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nanPositionX_rejectsMessage() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(Double.NaN, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_infinitePositionY_rejectsMessage() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, Double.POSITIVE_INFINITY), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullMessageAt_skipsBroadcast() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, null, null);

        forkliftLocationService.handleLocation(message);

        verifyNoInteractions(vehicleMapper);
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_headingNormalizedIntoZeroTo360Range() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage negative = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), -90.0, null, null, MESSAGE_AT);
        ForkliftLocationMessage over360 = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), 450.0, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(negative);
        forkliftLocationService.handleLocation(over360);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster, org.mockito.Mockito.times(2))
                .broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getAllValues().get(0).heading()).isEqualTo(270.0);
        assertThat(captor.getAllValues().get(1).heading()).isEqualTo(90.0);
    }

    @Test
    void handleLocation_nullHeading_isPassedThroughAsNull() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getValue().heading()).isNull();
    }

    @Test
    void handleLocation_fullQuaternion_isForwardedUnchanged() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage.Quaternion quaternion =
                new ForkliftLocationMessage.Quaternion(0.0, 0.0, 0.7071, 0.7071);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, quaternion, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        VehicleLocationEventData.Quaternion result = captor.getValue().quaternion();
        assertThat(result.x()).isEqualTo(0.0);
        assertThat(result.y()).isEqualTo(0.0);
        assertThat(result.z()).isEqualTo(0.7071);
        assertThat(result.w()).isEqualTo(0.7071);
    }

    @Test
    void handleLocation_partialQuaternion_rejectsMessage() {
        ForkliftLocationMessage.Quaternion quaternion =
                new ForkliftLocationMessage.Quaternion(0.0, 0.0, null, null);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, quaternion, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_allNullQuaternionFields_isTreatedAsNullQuaternion() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage.Quaternion quaternion =
                new ForkliftLocationMessage.Quaternion(null, null, null, null);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, quaternion, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getValue().quaternion()).isNull();
    }

    @Test
    void handleLocation_nullQuaternionObject_isAllowed() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), any(), any());
    }

    @Test
    void handleLocation_positiveSpeed_isForwarded() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, 0.4, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getValue().speed()).isEqualTo(0.4);
    }

    @Test
    void handleLocation_negativeSpeed_rejectsMessage() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, -0.1, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_nullSpeed_isAllowed() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), any(), any());
    }

    @Test
    void handleLocation_infiniteSpeed_rejectsMessage() {
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, position(1.0, 1.0), null, null, Double.POSITIVE_INFINITY, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    @Test
    void handleLocation_blankFrameId_normalizesToMap() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", null, new ForkliftLocationMessage.Position(1.0, 1.0, "  "),
                null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getValue().position().frameId()).isEqualTo("map");
    }

    @Test
    void handleLocation_doesNotTouchVehicleStatusMapperOrCurrentStatusUpsert() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = fullMessage("FORKLIFT-01");

        forkliftLocationService.handleLocation(message);

        // ForkliftLocationService는 VehicleMapper.existsByVehicleId 외 다른 상태 관련 Mapper/Service를
        // 전혀 참조하지 않는다(생성자 의존성 자체가 VehicleMapper·Broadcaster뿐) — vehicle_current_status
        // upsert 경로(VehicleStatusService/VehicleCurrentStatusMapper)가 이 클래스에 없다는 것이 설계로
        // 보장된다(prompt24.md 8장 "DB 저장은 이번 요구사항의 필수 범위가 아니다").
        verify(vehicleMapper, never()).findByVehicleId(any());
    }

    @Test
    void handleLocation_activeStatus_isForwardedAsActive() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", "ACTIVE", position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getValue().status()).isEqualTo(VehicleStatus.ACTIVE);
    }

    @Test
    void handleLocation_movingStatus_isPreservedAsMoving() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        ForkliftLocationMessage message = new ForkliftLocationMessage(
                "FORKLIFT-01", "MOVING", position(1.0, 1.0), null, null, null, MESSAGE_AT);

        forkliftLocationService.handleLocation(message);

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster).broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getValue().status()).isEqualTo(VehicleStatus.MOVING);
    }

    /**
     * 5Hz(0.2초 간격) 연속 수신을 흉내낸다(prompt25.md 8장). Thread.sleep 없이 5번을 곧바로 호출해
     * 모두 정상 처리·브로드캐스트되는지, 그리고 DB Mapper(existsByVehicleId 외)가 추가로 호출되지
     * 않는지 확인한다.
     */
    @Test
    void handleLocation_fiveConsecutiveMessages_allBroadcastWithActiveStatusAndOwnMessageAt() {
        when(vehicleMapper.existsByVehicleId("FORKLIFT-01")).thenReturn(true);
        OffsetDateTime[] messageAts = new OffsetDateTime[]{
                MESSAGE_AT, MESSAGE_AT.plusNanos(200_000_000), MESSAGE_AT.plusNanos(400_000_000),
                MESSAGE_AT.plusNanos(600_000_000), MESSAGE_AT.plusNanos(800_000_000)
        };

        for (OffsetDateTime messageAt : messageAts) {
            ForkliftLocationMessage message = new ForkliftLocationMessage(
                    "FORKLIFT-01", "ACTIVE", position(2.5, 4.1), 90.0, null, 0.4, messageAt);
            forkliftLocationService.handleLocation(message);
        }

        ArgumentCaptor<VehicleLocationEventData> captor = ArgumentCaptor.forClass(VehicleLocationEventData.class);
        verify(broadcaster, org.mockito.Mockito.times(5))
                .broadcastLocation(eq("FORKLIFT-01"), captor.capture(), any());
        assertThat(captor.getAllValues()).hasSize(5);
        assertThat(captor.getAllValues()).extracting(VehicleLocationEventData::status)
                .containsOnly(VehicleStatus.ACTIVE);
        assertThat(captor.getAllValues()).extracting(VehicleLocationEventData::messageAt)
                .containsExactly(messageAts);
        // existsByVehicleId 외에 다른 Mapper 메서드(특히 DB 쓰기 경로)가 호출되지 않았는지 확인 —
        // 5Hz라도 메시지당 추가 DB insert/update가 없어야 한다(prompt25.md 1.3장·8장).
        verify(vehicleMapper, org.mockito.Mockito.times(5)).existsByVehicleId("FORKLIFT-01");
        verify(vehicleMapper, never()).findByVehicleId(any());
    }

    private ForkliftLocationMessage fullMessage(String vehicleId) {
        ForkliftLocationMessage.Quaternion quaternion =
                new ForkliftLocationMessage.Quaternion(0.0, 0.0, 0.7071, 0.7071);
        return new ForkliftLocationMessage(
                vehicleId, "MOVING", position(2.5, 4.1), 90.0, quaternion, 0.4, MESSAGE_AT);
    }

    private ForkliftLocationMessage.Position position(Double x, Double y) {
        return new ForkliftLocationMessage.Position(x, y, "map");
    }
}
