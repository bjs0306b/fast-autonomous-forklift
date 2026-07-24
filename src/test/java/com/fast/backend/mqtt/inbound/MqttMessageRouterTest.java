package com.fast.backend.mqtt.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fast.backend.ai.service.AiCargoAnalysisService;
import com.fast.backend.config.mqtt.MqttProperties;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.command.service.VehicleCommandResultService;
import com.fast.backend.embedded.service.EmbeddedErrorService;
import com.fast.backend.station.service.StationMeasurementService;
import com.fast.backend.embedded.service.EmbeddedForkStatusService;
import com.fast.backend.forklift.service.ForkliftLocationService;
import com.fast.backend.forklift.service.ForkliftStatusService;
import com.fast.backend.isaac.service.IsaacForkliftLocationService;
import com.fast.backend.isaac.service.IsaacForkliftPathService;
import com.fast.backend.isaac.service.IsaacForkliftStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 실제 MQTT Broker 없이 순수 라우팅/역직렬화 로직만 검증하는 단위 테스트.
 */
class MqttMessageRouterTest {

    private MqttMessageRouter router;
    private ForkliftStatusService forkliftStatusService;
    private ForkliftLocationService forkliftLocationService;
    private AiCargoAnalysisService aiCargoAnalysisService;
    private IsaacForkliftLocationService isaacForkliftLocationService;
    private IsaacForkliftStatusService isaacForkliftStatusService;
    private IsaacForkliftPathService isaacForkliftPathService;
    private VehicleCommandResultService vehicleCommandResultService;
    private EmbeddedForkStatusService embeddedForkStatusService;
    private EmbeddedErrorService embeddedErrorService;
    private StationMeasurementService stationMeasurementService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MqttProperties.Topics topics = new MqttProperties.Topics(
                "forklift/+/status", "forklift/+/location", "forklift/+/path",
                "forklift/+/command-result", "forklift/+/fork-status", "forklift/+/error",
                "cargo/detected", "forklift/%s/command",                 "fast/station/+/measurement");
        MqttProperties properties = new MqttProperties(
                "tcp://localhost:1883", null, null,
                "fast-backend-inbound", "fast-backend-outbound",
                10, 30, true, true, 1, 5000L, 5000L, topics);
        MqttTopics mqttTopics = new MqttTopics(properties);

        forkliftStatusService = mock(ForkliftStatusService.class);
        forkliftLocationService = mock(ForkliftLocationService.class);
        aiCargoAnalysisService = mock(AiCargoAnalysisService.class);
        isaacForkliftLocationService = mock(IsaacForkliftLocationService.class);
        isaacForkliftStatusService = mock(IsaacForkliftStatusService.class);
        isaacForkliftPathService = mock(IsaacForkliftPathService.class);
        vehicleCommandResultService = mock(VehicleCommandResultService.class);
        embeddedForkStatusService = mock(EmbeddedForkStatusService.class);
        embeddedErrorService = mock(EmbeddedErrorService.class);
        stationMeasurementService = mock(StationMeasurementService.class);
        router = new MqttMessageRouter(
                objectMapper, mqttTopics, forkliftStatusService, forkliftLocationService, aiCargoAnalysisService,
                isaacForkliftLocationService, isaacForkliftStatusService, isaacForkliftPathService,
                vehicleCommandResultService, embeddedForkStatusService, embeddedErrorService,
                stationMeasurementService);
    }

    @Test
    void route_statusTopic_convertsToForkliftStatusMessage() {
        String payload = "{\"forkliftId\":\"F01\",\"status\":\"MOVING\",\"battery\":82,"
                + "\"timestamp\":\"2026-07-20T09:20:00+09:00\"}";

        router.route("forklift/F01/status", payload);

        verify(forkliftStatusService, times(1)).handleStatus(any());
        verifyNoInteractions(forkliftLocationService, isaacForkliftStatusService, isaacForkliftLocationService);
    }

    @Test
    void route_locationTopic_convertsToForkliftLocationMessage() {
        String payload = "{\"vehicleId\":\"F01\",\"status\":\"MOVING\","
                + "\"position\":{\"x\":120.5,\"y\":84.2,\"frameId\":\"map\"},\"heading\":90.0,"
                + "\"quaternion\":{\"x\":0.0,\"y\":0.0,\"z\":0.7071,\"w\":0.7071},\"speed\":1.2,"
                + "\"messageAt\":\"2026-07-20T09:20:00+09:00\"}";

        router.route("forklift/F01/location", payload);

        verify(forkliftLocationService, times(1)).handleLocation(any());
        verifyNoInteractions(forkliftStatusService, isaacForkliftLocationService, isaacForkliftStatusService);
    }

    // ---------- Isaac Sim 위치 (prompt28.md) ----------

    @Test
    void route_locationTopic_isaacForkliftIdPayload_dispatchesToIsaacLocationService() {
        String payload = "{\"forkliftId\":\"SIM01\",\"x\":1.234,\"y\":0.872,\"direction\":1.5708,"
                + "\"speed\":0.15,\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        router.route("forklift/SIM01/location", payload);

        verify(isaacForkliftLocationService, times(1)).handleLocation(any());
        verifyNoInteractions(forkliftLocationService, forkliftStatusService, isaacForkliftStatusService);
    }

    @Test
    void route_locationTopic_isaacVehicleIdMismatch_skipsService() {
        String payload = "{\"forkliftId\":\"SIM02\",\"x\":1.0,\"y\":1.0,\"direction\":0.0,"
                + "\"speed\":0.1,\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        router.route("forklift/SIM01/location", payload);

        verifyNoInteractions(isaacForkliftLocationService);
    }

    // ---------- Isaac Sim 상태·LWT (prompt28.md) ----------

    @Test
    void route_statusTopic_isaacFullStatusPayload_dispatchesToIsaacStatusService() {
        String payload = "{\"forkliftId\":\"SIM01\",\"status\":\"MOVING\",\"battery\":87,"
                + "\"forkHeight\":0.12,\"hasCargo\":true,\"cargoId\":\"BOX-0042\","
                + "\"footprint\":{\"length\":0.28,\"width\":0.16},\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        router.route("forklift/SIM01/status", payload);

        verify(isaacForkliftStatusService, times(1)).handleStatus(any());
        verifyNoInteractions(forkliftStatusService, forkliftLocationService, isaacForkliftLocationService);
    }

    @Test
    void route_statusTopic_isaacLwtOfflineMinimalPayload_dispatchesToIsaacStatusService() {
        // prompt28.md 5장 LWT 최소 메시지 — forkHeight/hasCargo/footprint/battery가 전부 없다.
        String payload = "{\"forkliftId\":\"SIM01\",\"status\":\"OFFLINE\",\"timestamp\":null}";

        router.route("forklift/SIM01/status", payload);

        verify(isaacForkliftStatusService, times(1)).handleStatus(any());
        verifyNoInteractions(forkliftStatusService);
    }

    @Test
    void route_locationTopic_activeStatusWithMillisecondMessageAt_convertsAndDelegates() {
        // prompt25.md 2장 최종 규격 — ACTIVE 상태, 밀리초 포함 messageAt.
        String payload = "{\"vehicleId\":\"F01\",\"status\":\"ACTIVE\","
                + "\"position\":{\"x\":2.5,\"y\":4.1,\"frameId\":\"map\"},\"heading\":90.0,"
                + "\"quaternion\":{\"x\":0.0,\"y\":0.0,\"z\":0.7071,\"w\":0.7071},\"speed\":0.4,"
                + "\"messageAt\":\"2026-07-22T13:30:00.123+09:00\"}";

        router.route("forklift/F01/location", payload);

        verify(forkliftLocationService, times(1)).handleLocation(any());
    }

    @Test
    void route_locationTopic_invalidMessageAtFormat_skipsServiceWithoutThrowing() {
        // prompt25.md 5장 "파싱 오류 처리" — JSON 역직렬화 자체가 실패해야 하므로 Service가 호출되지 않아야 한다.
        String payload = "{\"vehicleId\":\"F01\","
                + "\"position\":{\"x\":2.5,\"y\":4.1,\"frameId\":\"map\"},"
                + "\"messageAt\":\"not-a-valid-timestamp\"}";

        router.route("forklift/F01/location", payload);

        verifyNoInteractions(forkliftLocationService);
    }

    @Test
    void route_invalidMessageThenValidMessage_secondMessageStillProcessed() {
        // prompt25.md 5장 "다음 메시지 계속 처리" — 잘못된 messageAt 메시지 하나가 이후 정상 메시지
        // 처리에 영향을 주지 않아야 한다.
        String invalidPayload = "{\"vehicleId\":\"F01\","
                + "\"position\":{\"x\":2.5,\"y\":4.1,\"frameId\":\"map\"},"
                + "\"messageAt\":\"not-a-valid-timestamp\"}";
        String validPayload = "{\"vehicleId\":\"F01\","
                + "\"position\":{\"x\":2.5,\"y\":4.1,\"frameId\":\"map\"},"
                + "\"messageAt\":\"2026-07-22T13:30:00.123+09:00\"}";

        router.route("forklift/F01/location", invalidPayload);
        router.route("forklift/F01/location", validPayload);

        verify(forkliftLocationService, times(1)).handleLocation(any());
    }

    @Test
    void route_invalidJson_doesNotThrow() {
        router.route("forklift/F01/status", "not-a-json");

        verifyNoInteractions(forkliftStatusService);
        verifyNoInteractions(forkliftLocationService);
    }

    @Test
    void route_unknownTopic_doesNotThrow() {
        router.route("unknown/topic", "payload");

        verifyNoInteractions(forkliftStatusService);
        verifyNoInteractions(forkliftLocationService);
    }

    @Test
    void route_blankPayload_discardsWithoutCallingServices() {
        router.route("forklift/F01/status", " ");

        verifyNoInteractions(forkliftStatusService, forkliftLocationService, isaacForkliftStatusService,
                isaacForkliftLocationService, isaacForkliftPathService, vehicleCommandResultService,
                embeddedForkStatusService, embeddedErrorService);
    }

    @Test
    void route_nonObjectJson_discardsWithoutCallingServices() {
        router.route("forklift/F01/status", "[]");

        verifyNoInteractions(forkliftStatusService, forkliftLocationService, isaacForkliftStatusService,
                isaacForkliftLocationService, isaacForkliftPathService, vehicleCommandResultService,
                embeddedForkStatusService, embeddedErrorService);
    }

    @Test
    void route_statusServiceThrowsRuntimeException_doesNotPropagateAndNextMessageContinues() {
        String payload = "{\"forkliftId\":\"F01\",\"status\":\"MOVING\",\"battery\":82,"
                + "\"timestamp\":\"2026-07-20T09:20:00+09:00\"}";
        doThrow(new IllegalStateException("temporary service failure"))
                .doNothing()
                .when(forkliftStatusService)
                .handleStatus(any());

        router.route("forklift/F01/status", payload);
        router.route("forklift/F01/status", payload);

        verify(forkliftStatusService, times(2)).handleStatus(any());
    }

    @Test
    void route_cargoDetectedTopic_doesNotThrow() {
        // "any"는 AiCargoAnalysisMessage에 없는 필드라 Jackson이 알 수 없는 속성으로 역직렬화 자체를
        // 실패시킨다(JsonProcessingException) — 다른 라우팅 메서드와 동일하게 여기서 잡아 폐기하므로
        // Service까지는 위임되지 않는다.
        router.route("cargo/detected", "{\"any\":\"payload\"}");

        verifyNoInteractions(forkliftStatusService);
        verifyNoInteractions(forkliftLocationService);
        verifyNoInteractions(aiCargoAnalysisService);
    }

    @Test
    void route_cargoDetectedTopic_convertsToAiCargoAnalysisMessage() {
        String payload = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-001\","
                + "\"vehicleId\":\"FORKLIFT-01\",\"cargoId\":\"CARGO-001\",\"status\":\"ok\","
                + "\"detection\":{\"boxes\":[{\"className\":\"box\",\"confidence\":0.96,"
                + "\"bboxPx\":[120,80,340,260]}]},"
                + "\"distance\":{\"valueCm\":185.4,\"stdCm\":2.8},"
                + "\"message\":\"화물 분석이 완료되었습니다.\","
                + "\"capturedAt\":\"2026-07-22T13:30:00\",\"processedAt\":\"2026-07-22T13:30:01\"}";

        router.route("cargo/detected", payload);

        verify(aiCargoAnalysisService, times(1)).process(any());
        verifyNoInteractions(forkliftStatusService, forkliftLocationService);
    }

    @Test
    void route_cargoDetectedTopic_invalidJson_skipsServiceWithoutThrowing() {
        router.route("cargo/detected", "not-a-json");

        verifyNoInteractions(aiCargoAnalysisService);
    }

    @Test
    void route_cargoDetectedTopic_invalidProcessedAtFormat_skipsServiceWithoutThrowing() {
        // prompt27.md 10장·11장: 잘못된 시간 문자열이면 역직렬화 자체가 실패해 Service가 호출되지 않고,
        // 예외도 여기서 흡수돼 다음 메시지 처리에 영향을 주지 않아야 한다.
        String payload = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-BAD-TIME\",\"status\":\"ok\","
                + "\"processedAt\":\"not-a-valid-timestamp\"}";

        router.route("cargo/detected", payload);

        verifyNoInteractions(aiCargoAnalysisService);
    }

    @Test
    void route_cargoDetectedInvalidThenValid_secondMessageStillProcessed() {
        // prompt27.md 11장 "잘못된 메시지 하나 때문에 MQTT 소비가 중단되지 않아야 한다"를 재확인.
        String invalidPayload = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-BAD\",\"status\":\"ok\","
                + "\"processedAt\":\"not-a-valid-timestamp\"}";
        String validPayload = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-GOOD\",\"status\":\"ok\","
                + "\"processedAt\":\"2026-07-22T13:30:01\"}";

        router.route("cargo/detected", invalidPayload);
        router.route("cargo/detected", validPayload);

        verify(aiCargoAnalysisService, times(1)).process(any());
    }

    @Test
    void route_cargoDetectedTopic_serviceThrowsRuntimeException_doesNotPropagate() {
        // AiCargoAnalysisService#process는 DB insert 실패 등 예상치 못한 RuntimeException을 일부러
        // 밖으로 던진다(트랜잭션 롤백을 위해, AiCargoAnalysisService Javadoc 참고) — 그 예외가 여기까지
        // 올라와도 라우터가 최종적으로 흡수해 MQTT 소비 스레드를 보호해야 한다(prompt26.md 10장).
        doThrow(new RuntimeException("DB down")).when(aiCargoAnalysisService).process(any());
        String payload = "{\"schemaVersion\":\"1.0\",\"analysisId\":\"ANALYSIS-002\",\"status\":\"ok\","
                + "\"processedAt\":\"2026-07-22T13:30:01\"}";

        router.route("cargo/detected", payload);

        verify(aiCargoAnalysisService, times(1)).process(any());
    }

    @Test
    void route_statusTopic_vehicleIdMismatchWithPayload_skipsService() {
        // 토픽은 F01이지만 payload의 forkliftId는 F02 — prompt20.md 11장 "vehicleId 불일치" 시 처리 금지.
        String payload = "{\"forkliftId\":\"F02\",\"status\":\"MOVING\",\"battery\":82,"
                + "\"timestamp\":\"2026-07-20T09:20:00+09:00\"}";

        router.route("forklift/F01/status", payload);

        verifyNoInteractions(forkliftStatusService);
    }

    @Test
    void route_locationTopic_vehicleIdMismatchWithPayload_skipsService() {
        String payload = "{\"vehicleId\":\"F02\","
                + "\"position\":{\"x\":120.5,\"y\":84.2,\"frameId\":\"map\"},\"speed\":1.2,"
                + "\"messageAt\":\"2026-07-20T09:20:00+09:00\"}";

        router.route("forklift/F01/location", payload);

        verifyNoInteractions(forkliftLocationService);
    }

    // ---------- Isaac Sim 경로 (prompt28.md) ----------

    @Test
    void route_pathTopic_convertsToIsaacForkliftPathMessage() {
        String payload = "{\"forkliftId\":\"SIM01\","
                + "\"waypoints\":[{\"x\":1.20,\"y\":0.87},{\"x\":2.40,\"y\":0.87}],"
                + "\"goal\":{\"x\":2.40,\"y\":3.10,\"direction\":0.0},"
                + "\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        router.route("forklift/SIM01/path", payload);

        verify(isaacForkliftPathService, times(1)).handlePath(any());
        verifyNoInteractions(forkliftLocationService, forkliftStatusService, aiCargoAnalysisService);
    }

    @Test
    void route_pathTopic_vehicleIdMismatch_skipsService() {
        String payload = "{\"forkliftId\":\"SIM02\",\"waypoints\":[],"
                + "\"goal\":{\"x\":1.0,\"y\":1.0,\"direction\":0.0},"
                + "\"timestamp\":\"2026-07-22T10:30:00.123+09:00\"}";

        router.route("forklift/SIM01/path", payload);

        verifyNoInteractions(isaacForkliftPathService);
    }

    @Test
    void route_pathTopic_invalidJson_doesNotThrow() {
        router.route("forklift/SIM01/path", "not-a-json");

        verifyNoInteractions(isaacForkliftPathService);
    }

    // ---------- 실물 임베디드 명령 결과·포크 상태·오류 (prompt29.md) ----------

    @Test
    void route_commandResultTopic_convertsToVehicleCommandResultMessage() {
        String payload = "{\"commandId\":\"CMD-001\",\"forkliftId\":\"REAL01\",\"command\":\"FORK_UP\","
                + "\"result\":\"SUCCESS\",\"completedAt\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/command-result", payload);

        verify(vehicleCommandResultService, times(1)).handleResult(any());
        verifyNoInteractions(embeddedForkStatusService, embeddedErrorService);
    }

    @Test
    void route_commandResultTopic_vehicleIdMismatch_skipsService() {
        String payload = "{\"commandId\":\"CMD-001\",\"forkliftId\":\"REAL02\",\"command\":\"FORK_UP\","
                + "\"result\":\"SUCCESS\",\"completedAt\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/command-result", payload);

        verifyNoInteractions(vehicleCommandResultService);
    }

    @Test
    void route_commandResultTopic_invalidJson_doesNotThrow() {
        router.route("forklift/REAL01/command-result", "not-a-json");

        verifyNoInteractions(vehicleCommandResultService);
    }

    @Test
    void route_commandResultTopic_serviceThrowsRuntimeException_doesNotPropagate() {
        doThrow(new RuntimeException("DB down")).when(vehicleCommandResultService).handleResult(any());
        String payload = "{\"commandId\":\"CMD-001\",\"forkliftId\":\"REAL01\",\"command\":\"FORK_UP\","
                + "\"result\":\"SUCCESS\",\"completedAt\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/command-result", payload);

        verify(vehicleCommandResultService, times(1)).handleResult(any());
    }

    @Test
    void route_forkStatusTopic_convertsToEmbeddedForkStatusMessage() {
        String payload = "{\"forkliftId\":\"REAL01\",\"forkState\":\"STOPPED\",\"limitBottom\":false,"
                + "\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/fork-status", payload);

        verify(embeddedForkStatusService, times(1)).handleForkStatus(any());
        verifyNoInteractions(vehicleCommandResultService, embeddedErrorService);
    }

    @Test
    void route_forkStatusTopic_vehicleIdMismatch_skipsService() {
        String payload = "{\"forkliftId\":\"REAL02\",\"forkState\":\"STOPPED\",\"limitBottom\":false,"
                + "\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/fork-status", payload);

        verifyNoInteractions(embeddedForkStatusService);
    }

    @Test
    void route_forkStatusTopic_invalidJson_doesNotThrow() {
        router.route("forklift/REAL01/fork-status", "not-a-json");

        verifyNoInteractions(embeddedForkStatusService);
    }

    @Test
    void route_errorTopic_convertsToEmbeddedErrorMessage() {
        String payload = "{\"forkliftId\":\"REAL01\",\"errorCode\":\"E001\",\"errorSource\":\"DRIVE\","
                + "\"severity\":\"WARNING\",\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/error", payload);

        verify(embeddedErrorService, times(1)).handleError(any());
        verifyNoInteractions(vehicleCommandResultService, embeddedForkStatusService);
    }

    @Test
    void route_errorTopic_vehicleIdMismatch_skipsService() {
        String payload = "{\"forkliftId\":\"REAL02\",\"errorCode\":\"E001\",\"errorSource\":\"DRIVE\","
                + "\"severity\":\"WARNING\",\"timestamp\":\"2026-07-22T10:30:00+09:00\"}";

        router.route("forklift/REAL01/error", payload);

        verifyNoInteractions(embeddedErrorService);
    }

    @Test
    void route_errorTopic_invalidJson_doesNotThrow() {
        router.route("forklift/REAL01/error", "not-a-json");

        verifyNoInteractions(embeddedErrorService);
    }
}
