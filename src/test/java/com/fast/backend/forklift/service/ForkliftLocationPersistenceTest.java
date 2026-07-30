package com.fast.backend.forklift.service;

import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.location.InMemoryLatestVehicleLocationProvider;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 차량 위치 MQTT 규격(prompt83)의 <b>DB 저장 경로</b>를 검증한다.
 *
 * <p>기존 {@link ForkliftLocationServiceTest}는 브로드캐스트 변환을, 이 클래스는
 * {@code vehicle_current_status} 위치 부분 갱신을 본다. 저장 대상은
 * {@link VehicleCurrentStatusMapper#updateLocation}이며 전체 상태 upsert가 아니다 —
 * 위치 메시지가 battery/speed/Isaac 확장 필드를 지우면 안 되기 때문이다.
 *
 * <p>topic/payload vehicleId 불일치는 {@code MqttMessageRouterTest}가,
 * 중첩 {@code position} 역직렬화는 {@code ForkliftLocationMessageTest}가 이미 검증한다.
 * 여기서는 확정 규격 payload 원문이 그대로 파싱되는지만 한 번 더 확인한다.
 */
class ForkliftLocationPersistenceTest {

    private static final String TOPIC = "forklift/REAL-F01/location";
    private static final OffsetDateTime MESSAGE_AT =
            OffsetDateTime.of(2026, 7, 27, 10, 30, 45, 304_000_000, ZoneOffset.ofHours(9));

    private VehicleMapper vehicleMapper;
    private VehicleCurrentStatusMapper currentStatusMapper;
    private VehicleWebSocketBroadcaster broadcaster;
    private ForkliftLocationService service;

    @BeforeEach
    void setUp() {
        vehicleMapper = mock(VehicleMapper.class);
        currentStatusMapper = mock(VehicleCurrentStatusMapper.class);
        broadcaster = mock(VehicleWebSocketBroadcaster.class);
        service = new ForkliftLocationService(vehicleMapper, currentStatusMapper, broadcaster,
                new InMemoryLatestVehicleLocationProvider());
        when(vehicleMapper.existsByVehicleId("REAL-F01")).thenReturn(true);
    }

    /** 테스트 1: 확정 규격 정상 메시지가 위치 컬럼에 저장된다. */
    @Test
    void validMessage_updatesLocationColumnsOnly() {
        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "map", 0.0, MESSAGE_AT));

        verify(currentStatusMapper).updateLocation(
                eq("REAL-F01"), eq(1.0), eq(2.0), eq(0.0),
                eq(MESSAGE_AT.toLocalDateTime()), any(LocalDateTime.class));
        // 전체 상태 upsert는 절대 호출하지 않는다 — battery/speed/Isaac 필드가 null로 지워진다.
        verify(currentStatusMapper, never()).upsert(any());
    }

    /** 테스트 2: REAL_F01(underscore)은 잘못된 규격이므로 저장하지 않는다. 자동 변환도 하지 않는다. */
    @Test
    void underscoreVehicleId_isRejectedAndNotConverted() {
        when(vehicleMapper.existsByVehicleId("REAL_F01")).thenReturn(true);

        service.handleLocation("forklift/REAL_F01/location",
                message("REAL_F01", 1.0, 2.0, "map", 0.0, MESSAGE_AT));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
        // 하이픈 형태로 바꿔치기해서 저장하는 경로도 없어야 한다.
        verify(currentStatusMapper, never()).updateLocation(
                eq("REAL-F01"), anyDouble(), anyDouble(), anyDouble(), any(), any());
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    /** 테스트 2(84): odom은 규격 위반이다 — 저장도 중계도 하지 않는다. */
    @Test
    void odomFrameId_isRejectedEntirely() {
        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "odom", 0.0, MESSAGE_AT));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    /** 테스트 3(84): frameId 누락은 map으로 보정하지 않고 거부한다. */
    @Test
    void missingFrameId_isRejectedEntirely() {
        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, null, 0.0, MESSAGE_AT));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    /**
     * 테스트 9(84): 오프셋 표기가 달라도 같은 절대시각이면 중복으로 판단한다.
     * {@code 2026-07-27T10:30:45+09:00} == {@code 2026-07-27T01:30:45Z}
     */
    @Test
    void sameInstantWithDifferentOffset_isTreatedAsDuplicate() {
        when(currentStatusMapper.findByVehicleId("REAL-F01"))
                .thenReturn(Optional.of(storedWithMessageAt(MESSAGE_AT.toLocalDateTime())));

        OffsetDateTime sameInstantInUtc = MESSAGE_AT.withOffsetSameInstant(ZoneOffset.UTC);
        assertThat(sameInstantInUtc.toInstant()).isEqualTo(MESSAGE_AT.toInstant());
        assertThat(sameInstantInUtc.toLocalDateTime()).isNotEqualTo(MESSAGE_AT.toLocalDateTime());

        service.handleLocation(TOPIC, message("REAL-F01", 7.7, 7.7, "map", 0.0, sameInstantInUtc));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    /**
     * 테스트 11(84): stale 메시지는 received_at 도 갱신하지 않는다.
     * updateLocation 자체가 호출되지 않으므로 received_at 을 건드릴 경로가 없다
     * (SQL 쪽 보호는 VehicleCurrentStatusLocationUpsertH2Test 가 실제 DB로 검증한다).
     */
    @Test
    void staleMessage_doesNotTouchReceivedAt() {
        when(currentStatusMapper.findByVehicleId("REAL-F01"))
                .thenReturn(Optional.of(storedWithMessageAt(MESSAGE_AT.toLocalDateTime())));

        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "map", 0.0, MESSAGE_AT.minusMinutes(1)));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    /** 테스트 3-b: 허용 목록에 없는 frameId는 메시지 자체를 폐기한다(기존 정책 유지). */
    @Test
    void unsupportedFrameId_isRejectedEntirely() {
        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "base_link", 0.0, MESSAGE_AT));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
        verify(broadcaster, never()).broadcastLocation(any(), any(), any());
    }

    /** 테스트 6: 저장된 message_at보다 오래된 메시지는 최신 좌표를 덮어쓰지 않는다. */
    @Test
    void staleMessage_doesNotOverwriteNewerStoredLocation() {
        when(currentStatusMapper.findByVehicleId("REAL-F01"))
                .thenReturn(Optional.of(storedWithMessageAt(MESSAGE_AT.toLocalDateTime())));

        OffsetDateTime older = MESSAGE_AT.minusSeconds(5);
        service.handleLocation(TOPIC, message("REAL-F01", 9.9, 9.9, "map", 0.0, older));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    /** 같은 messageAt의 재전송(QoS 1 중복)도 무시한다 — 저장값이 흔들리지 않는다. */
    @Test
    void duplicateMessageAt_isIgnored() {
        when(currentStatusMapper.findByVehicleId("REAL-F01"))
                .thenReturn(Optional.of(storedWithMessageAt(MESSAGE_AT.toLocalDateTime())));

        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "map", 0.0, MESSAGE_AT));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    /** 저장된 message_at이 null이면(위치를 한 번도 못 받은 행) 정상 저장한다. */
    @Test
    void storedMessageAtNull_isPersisted() {
        when(currentStatusMapper.findByVehicleId("REAL-F01"))
                .thenReturn(Optional.of(storedWithMessageAt(null)));

        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "map", 0.0, MESSAGE_AT));

        verify(currentStatusMapper).updateLocation(
                eq("REAL-F01"), eq(1.0), eq(2.0), eq(0.0), any(), any());
    }

    /** 테스트 7: heading은 degree 그대로, [0,360)으로 정규화해 저장한다(-10 → 350, 370 → 10). */
    @Test
    void heading_isNormalizedIntoZeroTo360BeforePersist() {
        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "map", -10.0, MESSAGE_AT));
        service.handleLocation(TOPIC, message("REAL-F01", 1.0, 2.0, "map", 370.0, MESSAGE_AT.plusSeconds(1)));

        verify(currentStatusMapper).updateLocation(
                eq("REAL-F01"), anyDouble(), anyDouble(), eq(350.0), any(), any());
        verify(currentStatusMapper).updateLocation(
                eq("REAL-F01"), anyDouble(), anyDouble(), eq(10.0), any(), any());
    }

    /** 미등록 차량은 저장하지 않는다(FK 위반 전에 막는다). */
    @Test
    void unregisteredVehicle_isNotPersisted() {
        when(vehicleMapper.existsByVehicleId("REAL-F09")).thenReturn(false);

        service.handleLocation("forklift/REAL-F09/location",
                message("REAL-F09", 1.0, 2.0, "map", 0.0, MESSAGE_AT));

        verify(currentStatusMapper, never()).updateLocation(
                anyString(), anyDouble(), anyDouble(), anyDouble(), any(), any());
    }

    /** 테스트 5: 확정 규격 payload 원문이 중첩 position까지 그대로 역직렬화되고 저장까지 이어진다. */
    @Test
    void agreedPayloadJson_isDeserializedAndPersisted() throws Exception {
        String json = """
                {
                  "vehicleId": "REAL-F01",
                  "position": { "x": 1.0, "y": 2.0, "frameId": "map" },
                  "heading": 0.0,
                  "messageAt": "2026-07-27T10:30:45.304+09:00"
                }
                """;
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        ForkliftLocationMessage message = objectMapper.readValue(json, ForkliftLocationMessage.class);

        assertThat(message.vehicleId()).isEqualTo("REAL-F01");
        assertThat(message.position().x()).isEqualTo(1.0);
        assertThat(message.position().y()).isEqualTo(2.0);
        assertThat(message.position().frameId()).isEqualTo("map");
        assertThat(message.heading()).isEqualTo(0.0);
        assertThat(message.messageAt()).isEqualTo(MESSAGE_AT);

        service.handleLocation(TOPIC, message);

        verify(currentStatusMapper, times(1)).updateLocation(
                eq("REAL-F01"), eq(1.0), eq(2.0), eq(0.0),
                eq(MESSAGE_AT.toLocalDateTime()), any());
    }

    private ForkliftLocationMessage message(String vehicleId, double x, double y, String frameId,
            Double heading, OffsetDateTime messageAt) {
        return new ForkliftLocationMessage(
                vehicleId, null,
                new ForkliftLocationMessage.Position(x, y, frameId),
                heading, null, null, messageAt);
    }

    private VehicleCurrentStatus storedWithMessageAt(LocalDateTime messageAt) {
        VehicleCurrentStatus stored = new VehicleCurrentStatus();
        stored.setVehicleId("REAL-F01");
        stored.setMessageAt(messageAt);
        return stored;
    }
}
