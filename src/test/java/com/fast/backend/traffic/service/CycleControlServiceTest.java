package com.fast.backend.traffic.service;

import com.fast.backend.traffic.config.CycleProperties;
import com.fast.backend.traffic.config.LoopTrackProperties;
import com.fast.backend.traffic.domain.CyclePhase;
import com.fast.backend.traffic.domain.OperationState;
import com.fast.backend.traffic.domain.VehicleMotion;
import com.fast.backend.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 주기 주행 경로 검증 — 특히 <b>규칙 1(일방통행)</b>.
 *
 * <p>이식 과정에서 이 규칙이 통째로 빠져 있었다. {@code driveLoop} 는 구현돼 있었지만 대기 선회
 * 에만 쓰였고, 실제 주행 구간은 목적지 좌표를 그대로 목표로 줬다. 그러면 Nav2 가 최단 경로를
 * 잡아 창고 한가운데를 가로지르고, 통로가 아닌 곳에서 차들이 만나 서로를 막는다.
 */
class CycleControlServiceTest {

    /** 규격 §1 의 반시계 순환로. 둘레 67.0 */
    private static final double BAY_X = 16.5;
    private static final double BAY_Y = 5.0;

    private CycleGoalPublisher publisher;
    private OperationService operationService;
    private RackApproachProvider rackApproaches;
    private VehicleProcedureRegistry procedureRegistry;

    @BeforeEach
    void setUp() {
        publisher = mock(CycleGoalPublisher.class);
        operationService = mock(OperationService.class);
        rackApproaches = mock(RackApproachProvider.class);
        procedureRegistry = mock(VehicleProcedureRegistry.class);

        when(operationService.state()).thenReturn(OperationState.RUNNING);
        when(operationService.isDrivable(anyString())).thenReturn(true);
        when(publisher.publishGoal(anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(true);
        when(procedureRegistry.isBusy(anyString())).thenReturn(Optional.empty());
        when(rackApproaches.find(anyString())).thenReturn(Optional.empty());
    }

    private CycleControlService service() {
        return new CycleControlService(
                cycleProps(), loopProps(), operationService,
                publisher, rackApproaches, procedureRegistry);
    }

    private static CycleProperties cycleProps() {
        return new CycleProperties(
                true,
                new CycleProperties.Station(BAY_X, BAY_Y, 0.0),
                new CycleProperties.Station(15.5, 4.0, 1.5708),
                1.5,        // arriveTol
                4.0,        // approachTrigger — 규격 APPROACH_TRIGGER
                7_000L, 12_000L, 40_000L, 90_000L,
                0.15,
                Map.of("SIM-F02", List.of("A001")),
                Map.of("A", 2.60, "B", 11.95),
                2.0);
    }

    private static LoopTrackProperties loopProps() {
        return new LoopTrackProperties(
                List.of(new LoopTrackProperties.Corner(15.5, 4.0),
                        new LoopTrackProperties.Corner(15.5, 27.0),
                        new LoopTrackProperties.Corner(5.0, 27.0),
                        new LoopTrackProperties.Corner(5.0, 4.0)),
                2.0, 3.0, 10.0, 3000L, 20L, 0.3);
    }

    private static VehicleMotion at(double x, double y) {
        return new VehicleMotion("SIM-F02", x, y, 0.0, 1.0, VehicleStatus.MOVING, false);
    }

    /** 마지막으로 발행된 목표 좌표. */
    private double[] lastGoal() {
        ArgumentCaptor<Double> x = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Double> y = ArgumentCaptor.forClass(Double.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce())
                .publishGoal(anyString(), x.capture(), y.capture(), anyDouble());
        List<Double> xs = x.getAllValues();
        List<Double> ys = y.getAllValues();
        return new double[]{xs.get(xs.size() - 1), ys.get(ys.size() - 1)};
    }

    @Test
    @DisplayName("바이에서 멀면 목적지가 아니라 순환로 모서리를 목표로 준다 (규칙 1)")
    void 멀면_모서리로_돈다() {
        CycleControlService svc = service();

        // 왼쪽 통로 한가운데. 바이 진입점까지 호장이 4.0 을 한참 넘는다.
        svc.tick(List.of(at(5.0, 20.0)), Set.of());

        double[] goal = lastGoal();
        assertThat(goal[0]).as("바이(16.5) 로 직행하면 안 된다").isNotEqualTo(BAY_X);
        // 모서리 넷 중 하나여야 한다.
        assertThat(List.of(goal[0] + "," + goal[1]))
                .containsAnyOf("15.5,4.0", "15.5,27.0", "5.0,27.0", "5.0,4.0");
    }

    @Test
    @DisplayName("진입점이 가까워지면 순환로를 벗어나 바이로 직행한다")
    void 가까우면_바이로_직행한다() {
        CycleControlService svc = service();

        // 바닥 통로 오른쪽 끝 — 바이 진입점(15.5, 5.0 부근)까지 4m 이내.
        svc.tick(List.of(at(14.0, 4.0)), Set.of());

        double[] goal = lastGoal();
        assertThat(goal[0]).isEqualTo(BAY_X);
        assertThat(goal[1]).isEqualTo(BAY_Y);
    }

    @Test
    @DisplayName("한 번 진입하면 계속 직행한다 — 통로를 벗어나면 호장이 다시 커져 되돌아가 버린다")
    void 진입은_유지된다() {
        CycleControlService svc = service();

        svc.tick(List.of(at(14.0, 4.0)), Set.of());         // 진입 시작
        svc.tick(List.of(at(15.9, 4.6)), Set.of());         // 통로를 벗어나는 중

        double[] goal = lastGoal();
        assertThat(goal[0]).isEqualTo(BAY_X);
        assertThat(goal[1]).isEqualTo(BAY_Y);
    }

    @Test
    @DisplayName("바이를 남이 쓰는 중이면 멈추지 않고 순환로를 계속 돈다 (규칙 3)")
    void 바이가_점유면_대기_선회한다() {
        CycleControlService svc = service();

        // 다른 차가 먼저 진입해 바이를 예약한다.
        svc.tick(List.of(new VehicleMotion("SIM-F03", 14.0, 4.0, 0.0, 1.0,
                VehicleStatus.MOVING, false)), Set.of());

        svc.tick(List.of(at(14.0, 4.0)), Set.of());

        // SIM-F02 에게는 바이 좌표가 나가면 안 된다.
        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> x = ArgumentCaptor.forClass(Double.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce())
                .publishGoal(id.capture(), x.capture(), anyDouble(), anyDouble());
        for (int i = 0; i < id.getAllValues().size(); i++) {
            if ("SIM-F02".equals(id.getAllValues().get(i))) {
                assertThat(x.getAllValues().get(i))
                        .as("점유 중인 바이로 보내면 안 된다").isNotEqualTo(BAY_X);
            }
        }
    }

    @Test
    @DisplayName("주기가 꺼져 있으면 아무 목표도 보내지 않는다")
    void 꺼져_있으면_아무것도_안_한다() {
        CycleProperties off = new CycleProperties(
                false, new CycleProperties.Station(BAY_X, BAY_Y, 0.0),
                new CycleProperties.Station(15.5, 4.0, 1.5708),
                1.5, 4.0, 7_000L, 12_000L, 40_000L, 90_000L, 0.15,
                Map.of(), Map.of("A", 2.60), 2.0);
        CycleControlService svc = new CycleControlService(
                off, loopProps(), operationService, publisher, rackApproaches, procedureRegistry);

        svc.tick(List.of(at(5.0, 20.0)), Set.of());

        verify(publisher, times(0)).publishGoal(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("관제가 세워 둔 차량에는 목표를 보내지 않는다 (규칙 5)")
    void 세워_둔_차량은_건드리지_않는다() {
        CycleControlService svc = service();

        svc.tick(List.of(at(5.0, 20.0)), Set.of("SIM-F02"));

        verify(publisher, times(0)).publishGoal(anyString(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("선반 코드를 차량이 아는 이름으로 바꾼다 — A001 → A1")
    void 랙_코드를_차량_형식으로_바꾼다() {
        // 시뮬의 RACK_SLOTS 키는 A1..A12 / B1..B12 다(cargo_demo.py:321).
        // A001 을 그대로 보내면 조용히 실패해 아무것도 안 놓는다.
        assertThat(CycleControlService.toWireRackCode("A001")).isEqualTo("A1");
        assertThat(CycleControlService.toWireRackCode("B012")).isEqualTo("B12");
        assertThat(CycleControlService.toWireRackCode("A1")).isEqualTo("A1");
    }

    @Test
    @DisplayName("모르는 형식은 그대로 둔다 — 짐작해서 바꾸면 어느 쪽도 아닌 이름이 나간다")
    void 모르는_랙_코드는_그대로() {
        assertThat(CycleControlService.toWireRackCode("RACK-A-01")).isEqualTo("RACK-A-01");
        assertThat(CycleControlService.toWireRackCode(null)).isNull();
    }

    @Test
    @DisplayName("초기 단계는 TO_BAY 다")
    void 초기_단계() {
        CycleControlService svc = service();
        svc.tick(List.of(at(5.0, 20.0)), Set.of());
        assertThat(svc.cycleFor("SIM-F02").phase()).isEqualTo(CyclePhase.TO_BAY);
    }
}
