package com.fast.backend.vehicle.location;

import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.mqtt.inbound.MqttMessageRouter;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * MQTT 위치 메시지 한 건이 <b>DB · 인메모리 최신 위치 · STOMP 발행</b> 세 곳에 모두 도달하는지 확인한다.
 *
 * <p>실제 브로커와 브라우저를 뺀 전 구간을 실제 스프링 배선으로 통과시킨다.
 * <pre>
 * MQTT payload(JSON 문자열)
 *   → MqttMessageRouter.route()        ← 여기서 시작한다(브로커 소켓만 대체)
 *   → ForkliftLocationService
 *        ├ vehicle_current_status 갱신
 *        ├ LatestVehicleLocationProvider 갱신
 *        └ VehicleWebSocketBroadcaster → SimpMessagingTemplate.convertAndSend()
 * </pre>
 *
 * <p>확인 대상은 "호출됐는가"가 아니라 <b>필드와 단위가 끝까지 살아남는가</b>다 — 좌표 m, heading degree,
 * speed m/s, 그리고 화면의 "최근 수신 시간"이 쓰는 {@code receivedAt}.
 *
 * <p>차량 ID 에 접미사를 붙여 테스트마다 다르게 쓴다. {@code LatestVehicleLocationProvider} 는
 * 인메모리 싱글턴이라 트랜잭션 롤백으로 되돌아가지 않기 때문이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleLocationMqttToWebSocketFlowIntegrationTest {

    private static final String LOCATION_TOPIC_ALL = "/topic/vehicles/location";

    @Autowired private MqttMessageRouter router;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper statusMapper;
    @Autowired private LatestVehicleLocationProvider locationProvider;

    @MockBean private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("위치 메시지가 DB·인메모리·STOMP 세 곳에 모두 반영된다")
    void locationMessageReachesDatabaseMemoryAndWebSocket() {
        String vehicleId = "SIM-FLOW-01";
        insertVehicle(vehicleId);

        router.route(topic(vehicleId), payload(vehicleId, 12.34, 5.67, 90.0, 0.75,
                "2026-08-05T15:23:55.020+09:00"));

        // 1) DB — vehicle_current_status
        VehicleCurrentStatus stored = statusMapper.findByVehicleId(vehicleId).orElseThrow();
        assertThat(stored.getPositionX()).isEqualTo(12.34);
        assertThat(stored.getPositionY()).isEqualTo(5.67);
        assertThat(stored.getPositionFrame()).isEqualTo("map");
        assertThat(stored.getHeading()).isEqualTo(90.0);
        assertThat(stored.getSpeed()).isEqualTo(0.75);

        // 2) 인메모리 최신 위치 — 대시보드 REST 가 읽는 값
        VehicleLocationSnapshot snapshot = locationProvider.findLatest(vehicleId).orElseThrow();
        assertThat(snapshot.x()).isEqualTo(12.34);
        assertThat(snapshot.y()).isEqualTo(5.67);
        assertThat(snapshot.heading()).isEqualTo(90.0);
        assertThat(snapshot.speed()).isEqualTo(0.75);
        assertThat(snapshot.frameId()).isEqualTo("map");
        assertThat(snapshot.receivedAt()).isNotNull();

        // 3) STOMP — 전체 토픽과 차량별 토픽 두 곳에 발행한다
        VehicleLocationEventData data = captureLocationEvent(vehicleId);
        assertThat(data.vehicleId()).isEqualTo(vehicleId);
        assertThat(data.position().x()).isEqualTo(12.34);
        assertThat(data.position().y()).isEqualTo(5.67);
        assertThat(data.position().frameId()).isEqualTo("map");
        assertThat(data.heading()).isEqualTo(90.0);
        assertThat(data.speed()).isEqualTo(0.75);
        assertThat(data.messageAt()).isNotNull();
        // 화면의 "최근 수신 시간"과 신선도 판정이 이 값을 쓴다. 비어 있으면 P4 표시가 죽는다.
        assertThat(data.receivedAt()).isNotNull();

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq(LOCATION_TOPIC_ALL), any(Object.class));
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(eq(LOCATION_TOPIC_ALL + "/" + vehicleId), any(Object.class));
    }

    @Test
    @DisplayName("두 번째 위치 메시지가 값을 실시간으로 덮어쓴다")
    void secondMessageUpdatesTheStoredLocation() {
        String vehicleId = "SIM-FLOW-02";
        insertVehicle(vehicleId);

        router.route(topic(vehicleId), payload(vehicleId, 12.34, 5.67, 90.0, 0.75,
                "2026-08-05T15:23:55.020+09:00"));
        router.route(topic(vehicleId), payload(vehicleId, 13.10, 6.20, 95.0, 0.50,
                "2026-08-05T15:23:58.021+09:00"));

        VehicleCurrentStatus stored = statusMapper.findByVehicleId(vehicleId).orElseThrow();
        assertThat(stored.getPositionX()).isEqualTo(13.10);
        assertThat(stored.getPositionY()).isEqualTo(6.20);
        assertThat(stored.getHeading()).isEqualTo(95.0);
        assertThat(stored.getSpeed()).isEqualTo(0.50);

        VehicleLocationSnapshot snapshot = locationProvider.findLatest(vehicleId).orElseThrow();
        assertThat(snapshot.x()).isEqualTo(13.10);
        assertThat(snapshot.y()).isEqualTo(6.20);

        VehicleLocationEventData data = captureLocationEvent(vehicleId);
        assertThat(data.position().x()).isEqualTo(13.10);
        assertThat(data.heading()).isEqualTo(95.0);
    }

    @Test
    @DisplayName("미등록 차량의 위치는 폐기되고 어떤 경로에도 반영되지 않는다")
    void unregisteredVehicleIsDiscardedEverywhere() {
        String vehicleId = "SIM-FLOW-UNREGISTERED";

        router.route(topic(vehicleId), payload(vehicleId, 1.0, 2.0, 0.0, 0.0,
                "2026-08-05T15:23:55.020+09:00"));

        assertThat(statusMapper.findByVehicleId(vehicleId)).isEmpty();
        assertThat(locationProvider.findLatest(vehicleId)).isEmpty();
    }

    /** heading 은 [0,360) 으로 정규화해 내보낸다 — 프론트는 변환 없이 그대로 표시한다. */
    @Test
    @DisplayName("음수 heading 은 [0,360) degree 로 정규화된다")
    void negativeHeadingIsNormalizedToDegrees() {
        String vehicleId = "SIM-FLOW-HEADING";
        insertVehicle(vehicleId);

        router.route(topic(vehicleId), payload(vehicleId, 1.0, 2.0, -90.0, 0.0,
                "2026-08-05T15:23:55.020+09:00"));

        assertThat(locationProvider.findLatest(vehicleId).orElseThrow().heading()).isEqualTo(270.0);
        assertThat(captureLocationEvent(vehicleId).heading()).isEqualTo(270.0);
    }

    @SuppressWarnings("unchecked")
    private VehicleLocationEventData captureLocationEvent(String vehicleId) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(eq(LOCATION_TOPIC_ALL), captor.capture());

        List<Object> events = captor.getAllValues();
        for (int i = events.size() - 1; i >= 0; i--) {
            RealtimeEvent<Object> event = (RealtimeEvent<Object>) events.get(i);
            if (event.eventType() == RealtimeEventType.VEHICLE_LOCATION_UPDATED
                    && vehicleId.equals(event.vehicleId())) {
                return (VehicleLocationEventData) event.data();
            }
        }
        throw new AssertionError("No VEHICLE_LOCATION_UPDATED event was published for " + vehicleId);
    }

    private static String topic(String vehicleId) {
        return "forklift/" + vehicleId + "/location";
    }

    /** 실제 ROS2 발행 규격과 같은 JSON(중첩 position, degree heading, m/s speed). */
    private static String payload(
            String vehicleId, double x, double y, double heading, double speed, String messageAt) {
        return """
                {
                  "vehicleId": "%s",
                  "position": {"x": %s, "y": %s, "frameId": "map"},
                  "heading": %s,
                  "speed": %s,
                  "messageAt": "%s"
                }
                """.formatted(vehicleId, x, y, heading, speed, messageAt);
    }

    private void insertVehicle(String vehicleId) {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId);
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);
    }
}
