package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.embedded.service.EmbeddedErrorService;
import com.fast.backend.embedded.service.EmbeddedForkStatusService;
import com.fast.backend.forklift.dto.ForkliftLocationMessage;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftLocationService;
import com.fast.backend.isaac.service.IsaacForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.transport.dispatch.TransportCommandResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 다중 차량 MQTT 라우팅 분리 검증(prompt51.md 5장). 실제 Broker 없이 {@link MqttMessageRouter}를 직접
 * 호출하고 Mock Service 호출/인자로 차량 분리를 확인한다.
 */
class MultiVehicleMqttRoutingTest {

    private MqttMessageRouter router;
    private ForkliftStatusService forkliftStatusService;
    private ForkliftLocationService forkliftLocationService;
    private IsaacForkliftLocationService isaacForkliftLocationService;
    private VehicleCommandResultService vehicleCommandResultService;
    private TransportCommandResultService transportCommandResultService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqttProperties.Topics topics = new MqttProperties.Topics(
                "forklift/+/status", "forklift/+/location", "forklift/+/path",
                "forklift/+/command-result", "forklift/+/fork-status", "forklift/+/error",
                "cargo/detected", "forklift/%s/command",
                "forklift/+/load-safety");
        MqttTopics mqttTopics = new MqttTopics(new MqttProperties(
                "tcp://localhost:1883", null, null, "in", "out",
                10, 30, true, true, 1, 5000L, 5000L, topics));

        forkliftStatusService = mock(ForkliftStatusService.class);
        forkliftLocationService = mock(ForkliftLocationService.class);
        isaacForkliftLocationService = mock(IsaacForkliftLocationService.class);
        vehicleCommandResultService = mock(VehicleCommandResultService.class);
        transportCommandResultService = mock(TransportCommandResultService.class);
        router = new MqttMessageRouter(
                objectMapper, mqttTopics, forkliftStatusService, forkliftLocationService,
                mock(AiCargoAnalysisService.class), isaacForkliftLocationService,
                mock(IsaacForkliftStatusService.class), mock(IsaacForkliftPathService.class),
                vehicleCommandResultService, mock(EmbeddedForkStatusService.class),
                mock(EmbeddedErrorService.class),
                transportCommandResultService, mock(LoadSafetyService.class));
    }

    private static String status(String forkliftId) {
        return "{\"forkliftId\":\"" + forkliftId + "\",\"status\":\"MOVING\",\"battery\":81,"
                + "\"timestamp\":\"2026-07-27T15:00:00+09:00\"}";
    }

    private static String location(String vehicleId) {
        return "{\"vehicleId\":\"" + vehicleId + "\",\"position\":{\"x\":1.0,\"y\":2.0,\"frameId\":\"map\"},"
                + "\"heading\":90.0,\"messageAt\":\"2026-07-27T15:00:00+09:00\"}";
    }

    private static String result(String commandId, String vehicleId) {
        return "{\"commandId\":\"" + commandId + "\",\"vehicleId\":\"" + vehicleId + "\",\"result\":\"SUCCESS\","
                + "\"completedAt\":\"2026-07-27T15:00:00+09:00\"}";
    }

    @Test
    void matchingStatus_isRoutedWithCorrectVehicleId() {
        router.route("forklift/REAL-F01/status", status("REAL-F01"));

        ArgumentCaptor<ForkliftStatusMessage> captor = ArgumentCaptor.forClass(ForkliftStatusMessage.class);
        verify(forkliftStatusService).handleStatus(captor.capture());
        assertThat(captor.getValue().forkliftId()).isEqualTo("REAL-F01");
    }

    @Test
    void topicPayloadMismatch_realF01vsRealF02_isDiscarded() {
        router.route("forklift/REAL-F01/status", status("REAL-F02"));
        verifyNoInteractions(forkliftStatusService);
    }

    @Test
    void topicPayloadMismatch_simVsReal_isDiscarded() {
        router.route("forklift/SIM-F01/status", status("REAL-F01"));
        verifyNoInteractions(forkliftStatusService);
    }

    @Test
    void location_isRoutedToRos2ServiceWithCorrectVehicleId_notIsaac() {
        router.route("forklift/REAL-F01/location", location("REAL-F01"));

        ArgumentCaptor<ForkliftLocationMessage> captor = ArgumentCaptor.forClass(ForkliftLocationMessage.class);
        verify(forkliftLocationService).handleLocation(captor.capture());
        assertThat(captor.getValue().vehicleId()).isEqualTo("REAL-F01");
        verifyNoInteractions(isaacForkliftLocationService);
    }

    @Test
    void locationMismatch_realF01Topic_simPayload_isDiscarded() {
        router.route("forklift/REAL-F01/location", location("SIM-F01"));
        verifyNoInteractions(forkliftLocationService);
    }

    @Test
    void commandResult_isRoutedToBothServicesWithCorrectIds() {
        router.route("forklift/REAL-F01/command-result", result("TCMD-A", "REAL-F01"));

        ArgumentCaptor<VehicleCommandResultMessage> v = ArgumentCaptor.forClass(VehicleCommandResultMessage.class);
        verify(vehicleCommandResultService).handleResult(v.capture());
        assertThat(v.getValue().commandId()).isEqualTo("TCMD-A");
        assertThat(v.getValue().vehicleId()).isEqualTo("REAL-F01");
        ArgumentCaptor<VehicleCommandResultMessage> t = ArgumentCaptor.forClass(VehicleCommandResultMessage.class);
        verify(transportCommandResultService).handleResult(t.capture());
        assertThat(t.getValue().commandId()).isEqualTo("TCMD-A");
    }

    @Test
    void commandResultMismatch_isDiscarded() {
        router.route("forklift/REAL-F01/command-result", result("TCMD-A", "REAL-F02"));
        verifyNoInteractions(vehicleCommandResultService, transportCommandResultService);
    }

    @Test
    void malformedTopic_isNotRouted() {
        router.route("forklift/REAL-F01", status("REAL-F01")); // suffix 없음 → 미지원 토픽
        router.route("garbage-topic", status("REAL-F01"));
        verifyNoInteractions(forkliftStatusService, forkliftLocationService);
    }

    @Test
    void malformedJson_doesNotStopConsumer_nextMessageStillProcessed() {
        assertThatCode(() -> router.route("forklift/REAL-F01/status", "{not valid json"))
                .doesNotThrowAnyException();
        verify(forkliftStatusService, never()).handleStatus(any());

        // consumer 유지: 이후 정상 메시지는 정상 처리된다.
        router.route("forklift/REAL-F02/status", status("REAL-F02"));
        ArgumentCaptor<ForkliftStatusMessage> captor = ArgumentCaptor.forClass(ForkliftStatusMessage.class);
        verify(forkliftStatusService).handleStatus(captor.capture());
        assertThat(captor.getValue().forkliftId()).isEqualTo("REAL-F02");
    }

    @Test
    void unregisteredVehicle_isForwardedToService_registrationEnforcedDownstream() {
        // 라우터는 topic/payload가 일치하면 서비스로 전달한다. 미등록 차량 폐기는 서비스 계층 책임(정책 유지).
        router.route("forklift/GHOST-F09/status", status("GHOST-F09"));
        verify(forkliftStatusService).handleStatus(any());
    }
}
