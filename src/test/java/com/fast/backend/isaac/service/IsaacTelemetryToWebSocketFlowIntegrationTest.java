package com.fast.backend.isaac.service;

import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.mqtt.inbound.MqttMessageRouter;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.location.InMemoryLatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Isaac Sim telemetry 한 건이 <b>기존 위치 경로 전 구간</b>을 그대로 통과하는지 확인한다.
 *
 * <pre>
 * fast/v1/vehicle/sim01/telemetry (JSON)
 *   → MqttMessageRouter
 *   → IsaacVehicleTelemetryService   (sim01 → SIM-F01, radian → degree, epoch → OffsetDateTime)
 *   → VehicleLocationIngestionService
 *        ├ vehicle_current_status
 *        ├ LatestVehicleLocationProvider
 *        └ /topic/vehicles/location
 * </pre>
 *
 * <p>핵심은 <b>프론트가 두 출처를 구분할 필요가 없어야 한다</b>는 것이다. 그래서 검증 대상은 ROS2 경로와
 * 똑같은 필드·단위(중첩 position, degree heading, m/s speed, receivedAt)다.
 *
 * <p>이 테스트는 실제 브로커에 붙지 않는다. 브로커 소켓만 대체하고 나머지는 실제 스프링 배선이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class IsaacTelemetryToWebSocketFlowIntegrationTest {

    private static final String LOCATION_TOPIC_ALL = "/topic/vehicles/location";
    /** 별칭표에 sim01 → SIM-F01 이 있으므로 DB 에는 이 ID 로 등록돼 있어야 한다. */
    private static final String DB_VEHICLE_ID = "SIM-F01";

    @Autowired private MqttMessageRouter router;
    @Autowired private VehicleMapper vehicleMapper;
    @Autowired private VehicleCurrentStatusMapper statusMapper;
    @Autowired private InMemoryLatestVehicleLocationProvider locationProvider;

    @MockBean private SimpMessagingTemplate messagingTemplate;

    @BeforeEach
    void registerVehicle() {
        // 인메모리 위치는 트랜잭션 롤백으로 되돌아가지 않는다. 앞 테스트가 남긴 더 최신 messageAt 이
        // 있으면 이번 테스트의 위치가 아예 반영되지 않으므로, 실행 순서와 무관하도록 비우고 시작한다.
        locationProvider.clear();

        if (!vehicleMapper.existsByVehicleId(DB_VEHICLE_ID)) {
            LocalDateTime now = LocalDateTime.now();
            Vehicle vehicle = new Vehicle();
            vehicle.setVehicleId(DB_VEHICLE_ID);
            vehicle.setName("시뮬레이션 지게차 1호");
            vehicle.setActive(true);
            vehicle.setCreatedAt(now);
            vehicle.setUpdatedAt(now);
            vehicleMapper.insert(vehicle);
        }
    }

    @Test
    @DisplayName("sim01 telemetry 가 SIM-F01 로 정규화되어 DB·인메모리·STOMP 에 반영된다")
    void isaacTelemetryReachesEveryLocationConsumer() {
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId":"sim01","ts":1785946947471,
                 "pose":{"x":1.55,"y":0.4,"yaw":0.0},
                 "velocity":{"linear":0.0,"angular":0.0},
                 "forkHeight":0.0,"loaded":true,"cargoId":"C0007",
                 "cargo":{"id":"C0007","w":0.75,"d":0.91,"h":1.11},
                 "state":"IDLE","taskId":null,"battery":100.0}
                """);

        VehicleCurrentStatus stored = statusMapper.findByVehicleId(DB_VEHICLE_ID).orElseThrow();
        assertThat(stored.getPositionX()).isEqualTo(1.55);
        assertThat(stored.getPositionY()).isEqualTo(0.4);
        assertThat(stored.getPositionFrame()).isEqualTo("map");
        assertThat(stored.getHeading()).isEqualTo(0.0);
        assertThat(stored.getSpeed()).isEqualTo(0.0);
        assertThat(stored.getHasCargo()).isTrue();
        assertThat(stored.getCargoId()).isEqualTo(7L);

        VehicleLocationSnapshot snapshot = locationProvider.findLatest(DB_VEHICLE_ID).orElseThrow();
        assertThat(snapshot.x()).isEqualTo(1.55);
        assertThat(snapshot.y()).isEqualTo(0.4);
        assertThat(snapshot.receivedAt()).isNotNull();
        assertThat(snapshot.reportedLoaded()).isTrue();
        assertThat(snapshot.reportedCargoId()).isEqualTo("C0007");
        assertThat(snapshot.reportedCargoHeight()).isEqualTo(1.11);

        VehicleLocationEventData data = captureLocationEvent();
        assertThat(data.vehicleId()).isEqualTo(DB_VEHICLE_ID);   // 프론트에는 DB 기준 ID 를 보낸다
        assertThat(data.position().x()).isEqualTo(1.55);
        assertThat(data.position().y()).isEqualTo(0.4);
        assertThat(data.position().frameId()).isEqualTo("map");
        assertThat(data.heading()).isEqualTo(0.0);
        assertThat(data.speed()).isEqualTo(0.0);
        assertThat(data.receivedAt()).isNotNull();
        assertThat(data.reportedLoaded()).isTrue();
        assertThat(data.reportedCargoId()).isEqualTo("C0007");
        assertThat(data.reportedCargoHeight()).isEqualTo(1.11);

        verify(messagingTemplate, atLeastOnce()).convertAndSend(eq(LOCATION_TOPIC_ALL), any(Object.class));
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(eq(LOCATION_TOPIC_ALL + "/" + DB_VEHICLE_ID), any(Object.class));
    }

    @Test
    @DisplayName("두 번째 telemetry 의 yaw π/2 는 heading 90°, linear 0.75 는 speed 로 반영된다")
    void secondTelemetryUpdatesPositionHeadingAndSpeed() {
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId":"sim01","ts":1785946947471,
                 "pose":{"x":1.55,"y":0.4,"yaw":0.0},
                 "velocity":{"linear":0.0,"angular":0.0},"state":"IDLE"}
                """);
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId":"sim01","ts":1785946948471,
                 "pose":{"x":2.25,"y":1.10,"yaw":1.57079632679},
                 "velocity":{"linear":0.75,"angular":0.0},"state":"MOVING"}
                """);

        VehicleCurrentStatus stored = statusMapper.findByVehicleId(DB_VEHICLE_ID).orElseThrow();
        assertThat(stored.getPositionX()).isEqualTo(2.25);
        assertThat(stored.getPositionY()).isEqualTo(1.10);
        assertThat(stored.getHeading()).isCloseTo(90.0, within(1e-6));
        assertThat(stored.getSpeed()).isEqualTo(0.75);

        VehicleLocationEventData data = captureLocationEvent();
        assertThat(data.position().x()).isEqualTo(2.25);
        assertThat(data.heading()).isCloseTo(90.0, within(1e-6));
        assertThat(data.speed()).isEqualTo(0.75);
        assertThat(data.status()).isEqualTo(com.fast.backend.vehicle.domain.VehicleStatus.MOVING);
    }

    @Test
    @DisplayName("16. 오래된 ts 메시지는 최신 위치를 덮어쓰지 않는다")
    void olderTelemetryDoesNotOverwriteNewerLocation() {
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId":"sim01","ts":1785946948471,
                 "pose":{"x":5.0,"y":6.0,"yaw":0.0},
                 "velocity":{"linear":0.3,"angular":0.0},"state":"MOVING"}
                """);
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId":"sim01","ts":1785946947471,
                 "pose":{"x":1.0,"y":2.0,"yaw":0.0},
                 "velocity":{"linear":0.0,"angular":0.0},"state":"IDLE"}
                """);

        VehicleCurrentStatus stored = statusMapper.findByVehicleId(DB_VEHICLE_ID).orElseThrow();
        assertThat(stored.getPositionX()).isEqualTo(5.0);
        assertThat(stored.getPositionY()).isEqualTo(6.0);
    }

    /**
     * 2026-08-05 실제 브로커({@code 3.38.178.143:8883})에서 캡처한 payload 를 그대로 흘려 본다.
     *
     * <p>손으로 지어낸 예제만으로 검증하면 실제 발행자가 필드를 하나 더 붙이거나 이름을 바꿨을 때
     * 테스트는 통과하는데 운영은 깨진다. 실측 payload 를 고정해 두면 그 어긋남이 여기서 드러난다.
     */
    @Test
    @DisplayName("실제 브로커에서 캡처한 payload 가 그대로 처리된다")
    void capturedLivePayloadIsProcessed() {
        router.route("fast/v1/vehicle/sim01/telemetry", """
                {"vehicleId": "sim01", "ts": 1785948106161,
                 "pose": {"x": 2.039, "y": 0.722, "yaw": 0.8673},
                 "velocity": {"linear": 0.0, "angular": 0.0},
                 "forkHeight": 0.0, "loaded": false, "cargoId": null,
                 "state": "IDLE", "taskId": null, "battery": 100.0}
                """);

        VehicleLocationEventData data = captureLocationEvent();
        assertThat(data.vehicleId()).isEqualTo(DB_VEHICLE_ID);
        assertThat(data.position().x()).isEqualTo(2.039);
        assertThat(data.position().y()).isEqualTo(0.722);
        assertThat(data.heading()).isCloseTo(Math.toDegrees(0.8673), within(1e-9));
        assertThat(data.speed()).isEqualTo(0.0);
        assertThat(data.receivedAt()).isNotNull();
    }

    @Test
    @DisplayName("별칭이 없는 Isaac ID 는 DB 에 없으므로 폐기된다(sim99 를 새 차량으로 만들지 않는다)")
    void unmappedIsaacVehicleIsDiscarded() {
        router.route("fast/v1/vehicle/sim99/telemetry", """
                {"vehicleId":"sim99","ts":1785946947471,
                 "pose":{"x":1.0,"y":2.0,"yaw":0.0},
                 "velocity":{"linear":0.0,"angular":0.0},"state":"IDLE"}
                """);

        assertThat(statusMapper.findByVehicleId("sim99")).isEmpty();
        assertThat(locationProvider.findLatest("sim99")).isEmpty();
        assertThat(vehicleMapper.existsByVehicleId("sim99")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private VehicleLocationEventData captureLocationEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce())
                .convertAndSend(eq(LOCATION_TOPIC_ALL), captor.capture());

        List<Object> events = captor.getAllValues();
        for (int i = events.size() - 1; i >= 0; i--) {
            RealtimeEvent<Object> event = (RealtimeEvent<Object>) events.get(i);
            if (event.eventType() == RealtimeEventType.VEHICLE_LOCATION_UPDATED
                    && DB_VEHICLE_ID.equals(event.vehicleId())) {
                return (VehicleLocationEventData) event.data();
            }
        }
        throw new AssertionError("No VEHICLE_LOCATION_UPDATED event was published for " + DB_VEHICLE_ID);
    }
}
