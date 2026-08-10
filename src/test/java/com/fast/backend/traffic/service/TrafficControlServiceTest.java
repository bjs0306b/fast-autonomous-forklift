package com.fast.backend.traffic.service;

import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.service.VehicleCommandService;
import com.fast.backend.traffic.config.LoopTrackProperties;
import com.fast.backend.traffic.config.TrafficControlProperties;
import com.fast.backend.traffic.domain.OperationState;
import com.fast.backend.traffic.domain.TrafficControlEvent;
import com.fast.backend.traffic.domain.TrafficEventType;
import com.fast.backend.traffic.domain.TrafficHoldReason;
import com.fast.backend.traffic.domain.WorkZone;
import com.fast.backend.traffic.mapper.TrafficControlEventMapper;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 교통 관제 서비스 테스트 (FR-502-1a).
 *
 * <p>FR 이 명시한 정지/재개 조건과 <b>안전 기본값</b>(꺼져 있으면 아무 것도 안 함)을 고정한다.
 */
class TrafficControlServiceTest {

    private LatestVehicleLocationProvider locationProvider;
    private VehicleCurrentStatusMapper statusMapper;
    private WorkZoneProvider workZoneRegistry;
    private VehicleCommandService commandService;
    private TrafficControlEventMapper eventMapper;
    private OperationService operationService;
    private CycleControlService cycleControlService;

    private final List<VehicleLocationSnapshot> snapshots = new ArrayList<>();
    private final List<VehicleCurrentStatus> statuses = new ArrayList<>();
    private List<WorkZone> zones = List.of();

    @BeforeEach
    void setUp() {
        locationProvider = mock(LatestVehicleLocationProvider.class);
        statusMapper = mock(VehicleCurrentStatusMapper.class);
        workZoneRegistry = mock(WorkZoneProvider.class);
        commandService = mock(VehicleCommandService.class);
        eventMapper = mock(TrafficControlEventMapper.class);
        // 기존 테스트는 순수 차간 판정만 본다. 운행 상태는 늘 "주행 중"이고 개별 정지는 없다고 둔다.
        operationService = mock(OperationService.class);
        when(operationService.state()).thenReturn(OperationState.RUNNING);
        // 이 테스트는 차간 판정만 본다. 주기 진행은 별도 테스트(VehicleCycleTest)가 맡는다.
        cycleControlService = mock(CycleControlService.class);

        snapshots.clear();
        statuses.clear();
        zones = List.of();

        when(locationProvider.findAllLatest()).thenReturn(snapshots);
        when(statusMapper.findAllByVehicleIds(anyList())).thenReturn(statuses);
        when(workZoneRegistry.zones()).thenAnswer(inv -> zones);
    }

    private TrafficControlService service(TrafficControlProperties props) {
        return new TrafficControlService(
                props, loopProps(), operationService, cycleControlService,
                locationProvider, statusMapper, workZoneRegistry, commandService, eventMapper);
    }

    /**
     * 기존 테스트는 순환로를 신경 쓰지 않고 좌표를 자유롭게 쓴다. 그 좌표들이 우연히 순환로
     * 근처에 떨어지면 판단이 호장으로 바뀌어 기대값이 흔들린다. 그래서 <b>테스트용 순환로를
     * 아주 멀리</b> 두어 모든 차량이 "이탈" 로 판정되게 하고, 직선 거리 경로를 그대로 검증한다.
     * 호장 자체는 {@code TrackTest} 가 따로 고정한다.
     */
    private static LoopTrackProperties loopProps() {
        return new LoopTrackProperties(
                List.of(new LoopTrackProperties.Corner(1000, 1000),
                        new LoopTrackProperties.Corner(1000, 1100),
                        new LoopTrackProperties.Corner(1100, 1100),
                        new LoopTrackProperties.Corner(1100, 1000)),
                2.0, 3.0, 10.0, 3000L, 20L, 0.3);
    }

    private static TrafficControlProperties props(boolean enabled, String... vehicles) {
        return props(List.of(), enabled, vehicles);
    }

    private static TrafficControlProperties props(List<String> ignored, boolean enabled, String... vehicles) {
        return new TrafficControlProperties(
                enabled, 500, List.of(vehicles),
                6.0,    // safe
                9.0,    // hold
                1.2,    // hysteresis → release = 10.8
                2.0,    // horizon
                3.0,    // zone radius
                2000,   // stale ms
                ignored);
    }

    /** 지금 막 받은 위치(신선함). */
    private void location(String id, double x, double y, double headingDeg, double speed) {
        snapshots.add(new VehicleLocationSnapshot(
                id, x, y, headingDeg, speed, "map",
                OffsetDateTime.now(), OffsetDateTime.now(), null, null, null, null));
    }

    private void status(String id, VehicleStatus status) {
        VehicleCurrentStatus row = new VehicleCurrentStatus();
        row.setVehicleId(id);
        row.setStatus(status);
        statuses.add(row);
    }

    private List<String> issuedCommands(String vehicleId) {
        ArgumentCaptor<VehicleCommandRequest> captor =
                ArgumentCaptor.forClass(VehicleCommandRequest.class);
        verify(commandService, org.mockito.Mockito.atLeast(0))
                .issueCommand(eq(vehicleId), captor.capture());
        return captor.getAllValues().stream().map(VehicleCommandRequest::command).toList();
    }


    /** DB 에 기록된 이벤트(사유 포함)를 순서대로. */
    private List<TrafficControlEvent> recordedEvents() {
        ArgumentCaptor<TrafficControlEvent> captor =
                ArgumentCaptor.forClass(TrafficControlEvent.class);
        verify(eventMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 아무 명령도 보내지 않는다")
    void 꺼져_있으면_아무것도_안_한다() {
        location("F", 0, 0, 0, 2.0);
        location("L", 1, 0, 0, 0.0);      // 바로 앞 — 켜져 있었다면 반드시 정지
        status("F", VehicleStatus.MOVING);
        status("L", VehicleStatus.IDLE);

        service(props(false, "F", "L")).tick();

        verify(commandService, never()).issueCommand(any(), any());
    }

    @Test
    @DisplayName("제어 대상 목록이 비면 아무 명령도 보내지 않는다")
    void 대상_목록이_비면_아무것도_안_한다() {
        location("F", 0, 0, 0, 2.0);
        location("L", 1, 0, 0, 0.0);
        status("F", VehicleStatus.MOVING);
        status("L", VehicleStatus.IDLE);

        service(props(true)).tick();      // enabled 지만 vehicles 비어 있음

        verify(commandService, never()).issueCommand(any(), any());
    }

    @Test
    @DisplayName("안전거리 안으로 접근하면 후행 차량에 STOP")
    void 안전거리_침범시_후행에_STOP() {
        location("FOLLOWER", 0, 0, 0, 2.0);     // 동쪽으로 접근
        location("LEADER", 5, 0, 0, 0.0);       // 정지 중, 5m 앞 (hold 9m 미만)
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        service(props(true, "FOLLOWER", "LEADER")).tick();

        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
        // 접근하지 않는 선행 차량은 세우지 않는다.
        assertThat(issuedCommands("LEADER")).isEmpty();
    }

    @Test
    @DisplayName("ignored-vehicles 에 든 차량은 남을 막지 않는다")
    void 판단제외_차량은_후행을_세우지_않는다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("REAL-F01", 5, 0, 0, 0.0);     // 위 테스트와 같은 배치 — 원래는 STOP 이 나간다
        status("FOLLOWER", VehicleStatus.MOVING);
        status("REAL-F01", VehicleStatus.IDLE);

        // 제어 대상에서 빼는 것만으로는 부족하다. 그래서 vehicles 에는 남겨 두고
        // ignored-vehicles 로만 뺀다 — 그것이 실제로 겪은 상황이다(2026-08-10).
        service(props(List.of("REAL-F01"), true, "FOLLOWER", "REAL-F01")).tick();

        assertThat(issuedCommands("FOLLOWER")).isEmpty();
    }

    @Test
    @DisplayName("ignored-vehicles 에 들어도 그 차량 자신은 정지 판단을 받는다")
    void 판단제외_차량도_자기_정지는_받는다() {
        // 빼는 것은 "남이 볼 대상"뿐이다. 관측 목록째 빼면 주기 명령까지 끊긴다.
        location("REAL-F01", 0, 0, 0, 2.0);     // 앞차를 향해 접근
        location("LEADER", 5, 0, 0, 0.0);
        status("REAL-F01", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        service(props(List.of("REAL-F01"), true, "REAL-F01", "LEADER")).tick();

        assertThat(issuedCommands("REAL-F01")).containsExactly("STOP");
    }

    @Test
    @DisplayName("같은 상황이 계속돼도 STOP 을 반복 발행하지 않는다")
    void STOP_은_상태가_바뀔_때만_보낸다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();
        svc.tick();
        svc.tick();

        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
    }

    @Test
    @DisplayName("남의 작업 구역에 진입하려 하면 STOP")
    void 작업구역_침범시_STOP() {
        location("FOLLOWER", 8, 0, 0, 1.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        zones = List.of(new WorkZone("A1", 10, 0, 3.0, "LEADER"));

        service(props(true, "FOLLOWER")).tick();

        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
    }

    @Test
    @DisplayName("구역이 풀리고 안전거리가 확보되면 RESUME")
    void 해제되면_RESUME() {
        // 1) 정지시킨다
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");

        // 2) 선행 차량이 멀리 떠난다 (release = 9.0 × 1.2 = 10.8m 이상 필요)
        snapshots.clear();
        statuses.clear();
        location("FOLLOWER", 0, 0, 0, 0.0);     // 정지 상태로 대기 중
        location("LEADER", 30, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.HOLDING);
        status("LEADER", VehicleStatus.IDLE);

        svc.tick();

        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP", "RESUME");
    }

    @Test
    @DisplayName("경계 부근에서 STOP/RESUME 이 번갈아 나가지 않는다(채터링 방지)")
    void 히스테리시스로_채터링을_막는다() {
        location("FOLLOWER", 0, 0, 0, 0.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();     // 5m < hold 9m → STOP

        // 정지 임계(9m)는 넘겼지만 재개 임계(10.8m)에는 못 미치는 거리로 이동
        snapshots.clear();
        statuses.clear();
        location("FOLLOWER", 0, 0, 0, 0.0);
        location("LEADER", 10, 0, 0, 0.0);      // 10m — hold 는 벗어났지만 release 미만
        status("FOLLOWER", VehicleStatus.HOLDING);
        status("LEADER", VehicleStatus.IDLE);

        svc.tick();

        // 아직 재개하지 않는다.
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
    }

    @Test
    @DisplayName("위치가 낡은 차량은 판단에서 빠지고, 세워 둔 차량은 풀리지 않는다")
    void 낡은_위치는_재개시키지_않는다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");

        // 두 차량 모두 위치가 끊긴다 → 판단 대상에서 빠짐
        snapshots.clear();
        statuses.clear();
        snapshots.add(new VehicleLocationSnapshot(
                "FOLLOWER", 0.0, 0.0, 0.0, 0.0, "map",
                OffsetDateTime.now().minusMinutes(5), OffsetDateTime.now().minusMinutes(5),
                null, null, null, null));

        svc.tick();

        // 모르는 상태에서 재개시키지 않는다 — STOP 하나로 그대로.
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
        assertThat(svc.heldVehicles()).containsKey("FOLLOWER");
    }

    @Test
    @DisplayName("나를 세운 앞차의 위치가 끊기면, 내 위치가 멀쩡해도 재개하지 않는다")
    void 앞차_위치가_끊기면_재개하지_않는다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");

        // FOLLOWER 는 계속 신선하고, LEADER 만 끊긴다.
        // 예전에는 이때 "위반 상대가 없다 = 안전" 으로 읽혀 RESUME 이 나갔다 — 앞차가 어디
        // 있는지 모르는 채로 그쪽으로 다시 보내는 것이라 이 클래스의 안전 원칙과 정반대다.
        snapshots.clear();
        statuses.clear();
        location("FOLLOWER", 0, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.HOLDING);

        svc.tick();

        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
        assertThat(svc.heldVehicles()).containsKey("FOLLOWER");
    }

    @Test
    @DisplayName("전체 일시정지·비상정지 중에는 거리가 벌어져도 재개하지 않는다")
    void 전체_정지_중에는_재개하지_않는다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");

        // 사람이 전체 비상정지를 눌렀다. 그 뒤 앞차가 멀어져 거리 조건은 충족된다.
        when(operationService.state()).thenReturn(OperationState.ESTOPPED);
        snapshots.clear();
        statuses.clear();
        location("FOLLOWER", 0, 0, 0, 0.0);
        location("LEADER", 30, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.HOLDING);
        status("LEADER", VehicleStatus.IDLE);

        svc.tick();

        // 사람이 세워 둔 것을 기계가 풀면 안 된다.
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
    }

    @Test
    @DisplayName("제어 대상이 아닌 차량에는 명령하지 않는다(관측만)")
    void 대상이_아니면_명령하지_않는다() {
        location("CONTROLLED", 0, 0, 0, 2.0);
        location("OUTSIDER", 5, 0, 180, 2.0);   // 마주 접근 — 둘 다 위험
        status("CONTROLLED", VehicleStatus.MOVING);
        status("OUTSIDER", VehicleStatus.MOVING);

        service(props(true, "CONTROLLED")).tick();

        assertThat(issuedCommands("CONTROLLED")).containsExactly("STOP");
        verify(commandService, never()).issueCommand(eq("OUTSIDER"), any());
    }

    @Test
    @DisplayName("명령 발행이 실패해도 tick 이 죽지 않는다")
    void 발행_실패를_격리한다() {
        when(commandService.issueCommand(any(), any()))
                .thenThrow(new IllegalStateException("MQTT 발행 실패"));
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();     // 예외가 밖으로 나오면 스케줄러가 멈춘다

        verify(commandService, times(1)).issueCommand(eq("FOLLOWER"), any());
    }

    @Test
    @DisplayName("정지 사유가 DB 에 코드와 근거 값으로 남는다")
    void 정지_사유가_DB에_남는다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        service(props(true, "FOLLOWER", "LEADER")).tick();

        List<TrafficControlEvent> events = recordedEvents();
        assertThat(events).hasSize(1);
        TrafficControlEvent held = events.get(0);
        assertThat(held.getVehicleId()).isEqualTo("FOLLOWER");
        assertThat(held.getEventType()).isEqualTo(TrafficEventType.HOLD);
        assertThat(held.getReasonCode()).isEqualTo(TrafficHoldReason.SAFETY_DISTANCE);
        assertThat(held.getCounterpartVehicleId()).isEqualTo("LEADER");
        assertThat(held.getDistanceM()).isNotNull();
        assertThat(held.getOccurredAt()).isNotNull();
        assertThat(held.getReasonDetail()).contains("LEADER");
    }

    @Test
    @DisplayName("작업 구역 사유는 선반 코드와 점유자를 남긴다")
    void 구역_사유가_DB에_남는다() {
        location("FOLLOWER", 8, 0, 0, 1.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        zones = List.of(new WorkZone("A1", 10, 0, 3.0, "LEADER"));

        service(props(true, "FOLLOWER")).tick();

        TrafficControlEvent held = recordedEvents().get(0);
        assertThat(held.getReasonCode()).isEqualTo(TrafficHoldReason.WORK_ZONE_OCCUPIED);
        assertThat(held.getSlotCode()).isEqualTo("A1");
        assertThat(held.getCounterpartVehicleId()).isEqualTo("LEADER");
    }

    @Test
    @DisplayName("재개 이벤트는 직전 정지 사유를 함께 남긴다")
    void 재개_이벤트가_직전_사유를_남긴다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();

        snapshots.clear();
        statuses.clear();
        location("FOLLOWER", 0, 0, 0, 0.0);
        location("LEADER", 30, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.HOLDING);
        status("LEADER", VehicleStatus.IDLE);
        svc.tick();

        List<TrafficControlEvent> events = recordedEvents();
        assertThat(events).hasSize(2);
        TrafficControlEvent released = events.get(1);
        assertThat(released.getEventType()).isEqualTo(TrafficEventType.RELEASE);
        // 무엇이 풀려서 다시 갔는지 한 쌍으로 읽혀야 한다.
        assertThat(released.getReasonCode()).isEqualTo(TrafficHoldReason.SAFETY_DISTANCE);
        assertThat(released.getReasonDetail()).contains("해제됨");
    }

    @Test
    @DisplayName("같은 사유가 계속되는 동안은 이벤트를 반복 기록하지 않는다")
    void 같은_사유는_한_번만_기록한다() {
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        TrafficControlService svc = service(props(true, "FOLLOWER", "LEADER"));
        svc.tick();
        svc.tick();
        svc.tick();

        assertThat(recordedEvents()).hasSize(1);
    }

    @Test
    @DisplayName("이벤트 기록이 실패해도 관제는 계속된다")
    void 기록_실패를_격리한다() {
        org.mockito.Mockito.doThrow(new IllegalStateException("DB 없음"))
                .when(eventMapper).insert(any());
        location("FOLLOWER", 0, 0, 0, 2.0);
        location("LEADER", 5, 0, 0, 0.0);
        status("FOLLOWER", VehicleStatus.MOVING);
        status("LEADER", VehicleStatus.IDLE);

        service(props(true, "FOLLOWER", "LEADER")).tick();

        // 기록이 실패해도 정지 명령 자체는 나가야 한다.
        assertThat(issuedCommands("FOLLOWER")).containsExactly("STOP");
    }
}
