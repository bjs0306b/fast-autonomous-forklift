package com.fast.backend.isaac.service;

import com.fast.backend.traffic.service.VehicleProcedureRegistry;
import com.fast.backend.isaac.dto.IsaacVehicleTelemetryMessage;
import com.fast.backend.vehicle.config.VehicleProperties;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.location.VehicleLocationIngestion;
import com.fast.backend.vehicle.location.VehicleLocationIngestionService;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import com.fast.backend.vehicle.service.VehicleStatusService;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Isaac telemetry → 기존 위치 모델 변환 규칙 검증.
 *
 * <p>공통 처리기({@link VehicleLocationIngestionService})는 mock 으로 두고 <b>무엇을 넘기는가</b>만 본다.
 * DB·브로드캐스트까지의 반영은 통합 테스트가 따로 확인한다 — 여기서 같이 보면 변환 규칙이 깨졌을 때
 * 원인이 변환인지 저장인지 구분되지 않는다.
 */
class IsaacVehicleTelemetryServiceTest {

    private VehicleLocationIngestionService ingestionService;
    private VehicleStatusService statusService;
    private VehicleProcedureRegistry procedureRegistry;
    private IsaacVehicleTelemetryService service;

    @BeforeEach
    void setUp() {
        ingestionService = mock(VehicleLocationIngestionService.class);
        statusService = mock(VehicleStatusService.class);
        VehicleProperties properties = new VehicleProperties(
                false, false, 10L, Map.of("sim01", "SIM-F01", "sim02", "SIM-F02"));
        // busy/step 기록은 주기 상태기계 전용이라 이 테스트의 관심사가 아니다. 실제 구현을
        // 그대로 쓰되(가벼운 인메모리 맵), 검증은 VehicleProcedureRegistry 쪽에서 한다.
        procedureRegistry = new VehicleProcedureRegistry();
        service = new IsaacVehicleTelemetryService(
                new VehicleIdAliasResolver(properties), ingestionService, statusService,
                procedureRegistry);
    }

    @Test
    @DisplayName("1·2·6·10·11 정상 payload 를 파싱해 sim01 → SIM-F01 로 정규화하고 좌표·속도·시각을 옮긴다")
    void parsesAndNormalizesSim01() {
        service.handleTelemetry("sim01", telemetry("sim01", 1.55, 0.4, 0.0, 0.0, 1785946947471L));

        VehicleLocationIngestion ingestion = capture();
        assertThat(ingestion.vehicleId()).isEqualTo("SIM-F01");
        assertThat(ingestion.x()).isEqualTo(1.55);
        assertThat(ingestion.y()).isEqualTo(0.4);
        assertThat(ingestion.frameId()).isEqualTo("map");
        assertThat(ingestion.speed()).isEqualTo(0.0);
        // 1785946947471 ms = 2026-08-05T16:22:27.471Z 이며, 통신 규격대로 +09:00 으로 표기한다.
        assertThat(ingestion.messageAt().toInstant())
                .isEqualTo(java.time.Instant.ofEpochMilli(1785946947471L));
        assertThat(ingestion.messageAt())
                .isEqualTo(OffsetDateTime.parse("2026-08-06T01:22:27.471+09:00"));
    }

    @Test
    @DisplayName("3. sim02 → SIM-F02")
    void normalizesSim02() {
        service.handleTelemetry("sim02", telemetry("sim02", 1.0, 0.4, 0.0, 0.0, 1785946947471L));

        assertThat(capture().vehicleId()).isEqualTo("SIM-F02");
    }

    @Test
    @DisplayName("4. 별칭에 없는 ID 는 그대로 넘긴다(등록 여부는 공통 처리기가 판단해 폐기한다)")
    void unknownAliasIsPassedThroughUnchanged() {
        service.handleTelemetry("sim99", telemetry("sim99", 1.0, 2.0, 0.0, 0.0, 1785946947471L));

        // 여기서 버리지 않는 이유: 이미 DB 기준 ID 로 발행하는 경로(REAL-F01 등)도 같은 리졸버를 지난다.
        assertThat(capture().vehicleId()).isEqualTo("sim99");
    }

    @Test
    @DisplayName("4-1. 별칭 키는 대소문자를 무시한다")
    void aliasLookupIsCaseInsensitive() {
        service.handleTelemetry("SIM01", telemetry("SIM01", 1.0, 2.0, 0.0, 0.0, 1785946947471L));

        assertThat(capture().vehicleId()).isEqualTo("SIM-F01");
    }

    @Test
    @DisplayName("5. topic ID 와 payload ID 가 달라도 payload 를 신뢰하고 처리한다")
    void topicAndPayloadMismatchUsesPayload() {
        service.handleTelemetry("sim02", telemetry("sim01", 3.0, 4.0, 0.0, 0.0, 1785946947471L));

        assertThat(capture().vehicleId()).isEqualTo("SIM-F01");
    }

    @Test
    @DisplayName("7·8·9. yaw(radian) → heading(degree) 변환")
    void convertsYawRadiansToDegrees() {
        assertThat(headingOf(0.0)).isEqualTo(0.0);
        assertThat(headingOf(Math.PI / 2)).isCloseTo(90.0, within(1e-9));
        assertThat(headingOf(Math.PI)).isCloseTo(180.0, within(1e-9));
        assertThat(headingOf(-Math.PI / 2)).isCloseTo(-90.0, within(1e-9));
        assertThat(headingOf(2 * Math.PI)).isCloseTo(360.0, within(1e-9));
    }

    @Test
    @DisplayName("7-1. [0,360) 정규화는 공통 처리기가 한 번만 수행한다")
    void normalizationHappensInTheSharedIngestionService() {
        // 변환기는 raw degree 를 넘기고(-90, 360), 정규화 결과는 공통 처리기의 규칙을 따른다.
        assertThat(VehicleLocationIngestionService.normalizeHeading(-90.0)).isEqualTo(270.0);
        assertThat(VehicleLocationIngestionService.normalizeHeading(360.0)).isEqualTo(0.0);
        assertThat(VehicleLocationIngestionService.normalizeHeading(450.0)).isEqualTo(90.0);
    }

    @Test
    @DisplayName("10. velocity.linear → speed, velocity 가 없으면 null(0으로 단정하지 않는다)")
    void mapsLinearVelocityToSpeed() {
        service.handleTelemetry("sim01", telemetry("sim01", 1.0, 2.0, 0.0, 0.75, 1785946947471L));
        assertThat(capture().speed()).isEqualTo(0.75);

        setUp();
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1785946947471L, new IsaacVehicleTelemetryMessage.Pose(1.0, 2.0, 0.0),
                null, 0.0, false, null, "IDLE", null, 100.0));
        assertThat(capture().speed()).isNull();
    }

    @Test
    @DisplayName("11-1. ts 가 없으면 버리지 않고 백엔드 수신 시각으로 대체한다")
    void missingTimestampFallsBackToReceiveTime() {
        OffsetDateTime before = OffsetDateTime.now();

        service.handleTelemetry("sim01", telemetry("sim01", 1.0, 2.0, 0.0, 0.0, null));

        assertThat(capture().messageAt()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    @DisplayName("12. receivedAt 은 변환기가 만들지 않는다 — 공통 처리기가 백엔드 수신 시각으로 생성한다")
    void receivedAtIsCreatedByTheSharedIngestionService() {
        service.handleTelemetry("sim01", telemetry("sim01", 1.0, 2.0, 0.0, 0.0, 1785946947471L));

        // 입력 객체에는 receivedAt 자리가 없다. 값의 출처를 하나로 묶기 위한 의도적 설계다.
        VehicleLocationIngestion ingestion = capture();
        assertThat(ingestion.messageAt()).isNotNull();
    }

    @Test
    @DisplayName("13. pose 또는 x/y 가 없으면 폐기한다")
    void rejectsMissingPose() {
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1L, null, null, null, null, null, null, null, null));
        service.handleTelemetry("sim01", telemetry("sim01", null, 0.4, 0.0, 0.0, 1L));
        service.handleTelemetry("sim01", telemetry("sim01", 1.55, null, 0.0, 0.0, 1L));

        verify(ingestionService, never()).ingest(any(), anyString());
    }

    @Test
    @DisplayName("13-1. vehicleId 가 비어 있으면 폐기한다")
    void rejectsBlankVehicleId() {
        service.handleTelemetry("sim01", telemetry("  ", 1.0, 2.0, 0.0, 0.0, 1L));

        verify(ingestionService, never()).ingest(any(), anyString());
    }

    @Test
    @DisplayName("14. NaN·Infinity 좌표와 yaw 를 폐기한다")
    void rejectsNonFiniteValues() {
        service.handleTelemetry("sim01", telemetry("sim01", Double.NaN, 0.4, 0.0, 0.0, 1L));
        service.handleTelemetry("sim01", telemetry("sim01", 1.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 1L));
        service.handleTelemetry("sim01", telemetry("sim01", 1.0, 2.0, Double.NaN, 0.0, 1L));

        verify(ingestionService, never()).ingest(any(), anyString());
    }

    @Test
    @DisplayName("부가 필드(battery·forkHeight·loaded)가 이상해도 위치 표시를 막지 않는다")
    void brokenOptionalFieldsDoNotBlockLocation() {
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1785946947471L, new IsaacVehicleTelemetryMessage.Pose(1.55, 0.4, 0.0),
                new IsaacVehicleTelemetryMessage.Velocity(0.0, 0.0),
                Double.NaN, null, "not-a-number", "UNKNOWN-STATE", null, Double.NaN));

        assertThat(capture().x()).isEqualTo(1.55);
    }

    @Test
    @DisplayName("Isaac state 를 관제 상태로 변환하고, 모르는 값은 UNKNOWN 으로 흡수한다")
    void mapsStateForDisplayOnly() {
        service.handleTelemetry("sim01", telemetry("sim01", 1.0, 2.0, 0.0, 0.0, 1L));
        assertThat(capture().reportedStatus()).isEqualTo(VehicleStatus.IDLE);

        setUp();
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1L, new IsaacVehicleTelemetryMessage.Pose(1.0, 2.0, 0.0),
                null, null, null, null, "NAVIGATING", null, null));
        assertThat(capture().reportedStatus()).isEqualTo(VehicleStatus.UNKNOWN);

        setUp();
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1L, new IsaacVehicleTelemetryMessage.Pose(1.0, 2.0, 0.0),
                null, null, null, null, "LOWERING", null, null));
        assertThat(capture().reportedStatus()).isEqualTo(VehicleStatus.LIFTING);

        setUp();
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1L, new IsaacVehicleTelemetryMessage.Pose(1.0, 2.0, 0.0),
                null, null, null, null, "ESTOPPED", null, null));
        assertThat(capture().reportedStatus()).isEqualTo(VehicleStatus.ESTOP);
    }

    @Test
    @DisplayName("Isaac loaded/cargoId/state를 차량 상태 저장 경로로 전달한다")
    void forwardsCargoStateToVehicleStatusService() {
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1785946947471L, new IsaacVehicleTelemetryMessage.Pose(1.0, 2.0, 0.0),
                null, 0.7, true, "42", "MOVING", "TASK-1", 90.0));

        ArgumentCaptor<VehicleStatusUpdateCommand> captor =
                ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(statusService).updateCurrentStatus(org.mockito.ArgumentMatchers.eq("SIM-F01"), captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("MOVING");
        assertThat(captor.getValue().hasCargo()).isTrue();
        assertThat(captor.getValue().cargoId()).isEqualTo(42L);
        assertThat(captor.getValue().messageAt().toInstant())
                .isEqualTo(java.time.Instant.ofEpochMilli(1785946947471L));
        VehicleLocationIngestion ingestion = capture();
        assertThat(ingestion.forkHeight()).isEqualTo(0.7);
        assertThat(ingestion.battery()).isEqualTo(90.0);
        assertThat(ingestion.reportedCargoId()).isEqualTo("42");
        assertThat(ingestion.reportedTaskId()).isEqualTo("TASK-1");
    }

    @Test
    @DisplayName("Isaac cargo 객체의 문자열 ID·전체 높이·적재 여부를 보존한다")
    void forwardsNestedCargoTelemetry() {
        service.handleTelemetry("sim01", new IsaacVehicleTelemetryMessage(
                "sim01", 1785946947471L,
                new IsaacVehicleTelemetryMessage.Pose(1.0, 2.0, 0.0),
                null, 0.7, null, null, "MOVING", "TASK-1", 90.0,
                new IsaacVehicleTelemetryMessage.Cargo("C0007", 0.75, 0.91, 1.11)));

        VehicleLocationIngestion ingestion = capture();
        assertThat(ingestion.reportedLoaded()).isTrue();
        assertThat(ingestion.reportedCargoId()).isEqualTo("C0007");
        assertThat(ingestion.reportedCargoHeight()).isEqualTo(1.11);

        ArgumentCaptor<VehicleStatusUpdateCommand> captor =
                ArgumentCaptor.forClass(VehicleStatusUpdateCommand.class);
        verify(statusService).updateCurrentStatus(org.mockito.ArgumentMatchers.eq("SIM-F01"), captor.capture());
        assertThat(captor.getValue().hasCargo()).isTrue();
        assertThat(captor.getValue().cargoId()).isEqualTo(7L);
    }

    private double headingOf(double yawRadians) {
        setUp();
        service.handleTelemetry("sim01", telemetry("sim01", 1.0, 2.0, yawRadians, 0.0, 1L));
        return capture().heading();
    }

    private VehicleLocationIngestion capture() {
        ArgumentCaptor<VehicleLocationIngestion> captor =
                ArgumentCaptor.forClass(VehicleLocationIngestion.class);
        verify(ingestionService).ingest(captor.capture(), anyString());
        return captor.getValue();
    }

    private static IsaacVehicleTelemetryMessage telemetry(
            String vehicleId, Double x, Double y, Double yaw, Double linear, Long ts) {
        return new IsaacVehicleTelemetryMessage(
                vehicleId,
                ts,
                new IsaacVehicleTelemetryMessage.Pose(x, y, yaw),
                new IsaacVehicleTelemetryMessage.Velocity(linear, 0.0),
                0.0, false, null, "IDLE", null, 100.0);
    }
}
