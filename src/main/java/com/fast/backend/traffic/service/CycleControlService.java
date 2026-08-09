package com.fast.backend.traffic.service;

import com.fast.backend.traffic.config.CycleProperties;
import com.fast.backend.traffic.config.LoopTrackProperties;
import com.fast.backend.traffic.domain.Track;
import com.fast.backend.traffic.domain.VehicleCycle;
import com.fast.backend.traffic.domain.VehicleMotion;
import com.fast.backend.traffic.dto.CargoActionMessage;
import com.fast.backend.traffic.dto.PlaceRackTaskMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주기 상태기계 — 화물 한 개를 바이에서 받아 랙에 놓기까지를 진행시킨다
 * (F팀 규격 {@code backend-control-impl} §5, 규칙 1·3·4).
 *
 * <pre>
 *   TO_BAY ──arrived──▶ ALIGN_BAY ──▶ LOAD ──loaded──▶ TO_EXIT ──▶ TO_RACK ──▶ RACK ──▶ (주기+1)
 * </pre>
 *
 * <p><b>바이 도착은 차량이 알려 준다.</b> {@code forklift/{id}/arrived} 를 받으면
 * {@link #onArrived} 가 호출되고 거기서 단계가 넘어간다. 좌표로 자체 판정하지 않는 이유:
 * 반경 안에 들어온 것과 <b>정면으로 붙은 것</b>은 다르고, 그 차이는 좌표만으로 알 수 없다.
 * 다만 신호가 끝내 오지 않는 경우를 대비해 좌표 도달을 <b>보조 판정</b>으로 함께 둔다.
 *
 * <p><b>시간만으로 단계를 넘기지 않는다.</b> 적재 실패를 못 보고 넘어가면 빈 포크로 랙까지
 * 가서 아무것도 못 놓고 계속 순환한다(규격이 겪었다고 명시한 사고). 그래서
 * {@code loaded} 를 확인하고, 시간 제한은 무한 대기를 막는 안전장치로만 쓴다.
 *
 * <p>이 서비스는 <b>목표만 정한다.</b> 차간 유지·정지는 {@link TrafficControlService} 몫이고,
 * 그쪽이 세워 둔 차량에는 목표를 보내지 않는다.
 */
@Service
public class CycleControlService {

    private static final Logger log = LoggerFactory.getLogger(CycleControlService.class);

    private final CycleProperties cycleProperties;
    private final LoopTrackProperties loopProperties;
    private final Track track;
    private final OperationService operationService;
    private final CycleGoalPublisher publisher;
    private final RackApproachProvider rackApproaches;
    private final VehicleProcedureRegistry procedureRegistry;

    private final Map<String, VehicleCycle> cycles = new ConcurrentHashMap<>();

    /**
     * 입고 바이 예약자 (규칙 3). {@code null} 이면 비어 있다.
     *
     * <p>바이는 한 대만 쓴다. <b>다른 차는 멈추지 않고 순환로를 계속 돈다</b> —
     * 차선에 세우면 뒤차가 막혀 교착이 난다(규격이 지적한 핵심).
     */
    private volatile String bayOwner;

    /** 차량이 보낸 도착 신호. tick 이 소비하고 지운다. */
    private final Map<String, Boolean> arrivedSignals = new ConcurrentHashMap<>();

    public CycleControlService(
            CycleProperties cycleProperties,
            LoopTrackProperties loopProperties,
            OperationService operationService,
            CycleGoalPublisher publisher,
            RackApproachProvider rackApproaches,
            VehicleProcedureRegistry procedureRegistry) {
        this.cycleProperties = cycleProperties;
        this.loopProperties = loopProperties;
        this.track = loopProperties.toTrack();
        this.operationService = operationService;
        this.publisher = publisher;
        this.rackApproaches = rackApproaches;
        this.procedureRegistry = procedureRegistry;
    }

    /**
     * 차량이 입고 바이에 도착했다고 알려 왔다 ({@code forklift/{id}/arrived}).
     *
     * <p>여기서 바로 단계를 넘기지 않고 <b>신호만 남긴다.</b> MQTT 수신 스레드와 tick 스레드가
     * 같은 상태를 동시에 고치면, 도착 처리 도중에 tick 이 목표를 덮어쓰는 경합이 생긴다.
     */
    public void onArrived(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return;
        }
        arrivedSignals.put(vehicleId, Boolean.TRUE);
        log.info("바이 도착 신호 수신: vehicleId={}", vehicleId);
    }

    /** 관제가 세워 둔 차량 목록(정체 감시 시각을 미뤄야 하는 대상). */
    public void tick(List<VehicleMotion> motions, java.util.Set<String> heldByTraffic) {
        if (!Boolean.TRUE.equals(cycleProperties.enabled())) {
            return;     // F팀 데모와 병행 중이면 목표를 보내지 않는다
        }
        if (!operationService.state().isDriving()) {
            return;
        }
        long now = System.currentTimeMillis();

        for (VehicleMotion motion : motions) {
            String vehicleId = motion.vehicleId();
            if (!operationService.isDrivable(vehicleId)) {
                // 합류 전이거나 사람이 세워 둔 차량. 정체 감시 시각만 미뤄 둔다 —
                // 안 그러면 해제 직후 정체로 오판해 목표를 재전송한다(규격 §10 함정 7번).
                cycleOf(vehicleId, now).touchStallTimer(now);
                continue;
            }
            VehicleCycle cycle = cycleOf(vehicleId, now);

            if (heldByTraffic.contains(vehicleId)) {
                cycle.touchStallTimer(now);     // 규칙 5 — 세워 둔 동안은 정체가 아니다
                continue;
            }
            advance(cycle, motion, now);
        }
    }

    /** 한 차량의 단계를 진행시킨다. */
    private void advance(VehicleCycle cycle, VehicleMotion motion, long now) {
        switch (cycle.phase()) {
            case TO_BAY -> driveToBay(cycle, motion, now);
            case ALIGN_BAY -> alignBay(cycle, motion, now);
            case LOAD -> load(cycle, motion, now);
            case TO_EXIT -> driveToExit(cycle, motion, now);
            case TO_RACK -> driveToRack(cycle, motion, now);
            case RACK -> placeRack(cycle, motion, now);
        }
    }

    // ── TO_BAY ──────────────────────────────────────────────────────────────────

    /**
     * 순환로를 돌아 바이로 간다.
     *
     * <p>규칙 3 — 바이가 다른 차 차지면 <b>멈추지 않고 순환로를 계속 돈다.</b>
     */
    private void driveToBay(VehicleCycle cycle, VehicleMotion motion, long now) {
        cycle.setTarget("BAY");
        CycleProperties.Station bay = cycleProperties.bay();

        if (cycle.isApproaching()) {
            boolean arrivedSignal = Boolean.TRUE.equals(arrivedSignals.remove(cycle.vehicleId()));
            // 신호가 오지 않는 경우를 대비한 보조 판정. 신호가 정본이고 이쪽은 안전망이다.
            boolean nearBay = distance(motion, bay.x(), bay.y()) <= cycleProperties.arriveTolM();
            if (arrivedSignal || nearBay) {
                log.info("주기 단계 전이 TO_BAY → ALIGN_BAY: vehicleId={}, 근거={}",
                        cycle.vehicleId(), arrivedSignal ? "arrived 신호" : "좌표 도달");
                cycle.advance(now);
                return;
            }
            sendGoal(cycle, motion, now, bay.x(), bay.y(), bay.yaw(), "BAY");
            return;
        }

        // 아직 순환 중 — 진입점이 멀면 모서리를 하나씩 돈다(규칙 1).
        if (gapToEntry(motion, bay.x(), bay.y()) > cycleProperties.approachTriggerM()) {
            driveLoop(cycle, motion, now);
            return;
        }
        // 진입점에 닿았다. 규칙 3 — 바이는 한 대만 쓴다. 남이 쓰는 중이면 멈추지 않고 계속 돈다.
        if (bayOwner != null && !bayOwner.equals(cycle.vehicleId())) {
            driveLoop(cycle, motion, now);      // 대기 선회 — 차선에 서지 않는다
            return;
        }
        // 예약은 <b>빠져나가는 순간</b>에만 한다. 멀리서 미리 잡으면 그 차가 순환로를 도는
        // 내내 바이가 묶여, 바로 옆을 지나는 다른 차가 못 들어간다.
        bayOwner = cycle.vehicleId();
        cycle.markApproaching();
        sendGoal(cycle, motion, now, bay.x(), bay.y(), bay.yaw(), "BAY");
    }

    // ── ALIGN_BAY ───────────────────────────────────────────────────────────────

    /**
     * 바이 정면 정렬. 차량이 스스로 수행하므로 지시만 한 번 보내고 기다린다.
     *
     * <p><b>{@code state != LOADING} 으로 판정하면 안 된다</b>(규격 §5 명시). 정렬이 끝나도
     * {@code state} 는 화물을 받을 때까지 {@code LOADING} 을 유지하므로 구별이 안 되고,
     * 지시 직후에는 아직 {@code IDLE} 이라 <b>시작도 안 했는데 "끝났다"로 읽힌다.</b>
     *
     * <p>규격이 정한 조건은 <b>둘 다</b> 만족이다.
     * <pre>
     *   경과 &lt; ALIGN_MIN_MS(7000)  →  아직
     *   경과 &gt; ALIGN_MAX_MS(12000) →  busy 와 무관하게 진행 (안전장치)
     *   그 사이                      →  busy == false 여야 완료
     * </pre>
     *
     * <p>{@code busy} 는 시뮬만 보낸다. 실물(fk01)은 C팀 구현 전까지 이 필드가 없으므로
     * <b>최소 대기 시간만으로</b> 판정한다 — 모르는 값을 {@code false} 로 단정하면 실물이
     * 정렬 중인데 다음 단계로 넘어간다.
     */
    private void alignBay(VehicleCycle cycle, VehicleMotion motion, long now) {
        cycle.setTarget("ALIGN");
        if (cycle.shouldSendGoal("align_bay")) {
            publisher.publishCargo(cycle.vehicleId(), CargoActionMessage.alignBay());
        }
        if (!cycle.workSettled(now, cycleProperties.alignMinDwellMs())) {
            return;     // 최소 대기 전 — 정렬이 시작되지도 않았다
        }
        if (cycle.workTimedOut(now, cycleProperties.alignTimeoutMs())) {
            log.warn("정렬 상한 초과 — busy 와 무관하게 진행: vehicleId={}", cycle.vehicleId());
            cycle.advance(now);
            return;
        }
        // busy 를 안 보내는 차량(실물)은 최소 대기만으로 끝난 것으로 본다.
        boolean done = procedureRegistry.isBusy(cycle.vehicleId()).map(busy -> !busy).orElse(true);
        if (done) {
            cycle.advance(now);
        }
    }

    // ── LOAD ────────────────────────────────────────────────────────────────────

    /**
     * 화물 받기. <b>{@code loaded == true} 를 확인해야</b> 넘어간다.
     *
     * <p>시간만 보고 넘기면 적재에 실패한 차가 빈 포크로 랙까지 간다.
     */
    private void load(VehicleCycle cycle, VehicleMotion motion, long now) {
        cycle.setTarget("LOAD");
        if (cycle.shouldSendGoal("load")) {
            publisher.publishCargo(cycle.vehicleId(),
                    CargoActionMessage.load(cycleProperties.cargoHeightM(), null));
        }
        if (isLoaded(motion)) {
            cycle.advance(now);
            return;
        }
        if (cycle.workTimedOut(now, cycleProperties.loadTimeoutMs())) {
            // 실패다. 주기를 억지로 이어 가지 않고 바이를 반납한 뒤 처음부터 다시 돈다.
            log.warn("적재 시간 초과 — 주기를 재시작한다: vehicleId={}", cycle.vehicleId());
            releaseBay(cycle.vehicleId());
            restart(cycle, now);
        }
    }

    // ── TO_EXIT ─────────────────────────────────────────────────────────────────

    /**
     * 바이 탈출. 여기서 바이를 반납해 다음 차가 들어올 수 있게 한다.
     *
     * <p><b>여기만 순환로를 타지 않는다.</b> EXIT 는 바이 바로 뒤(약 1.8m)라, 규칙 1 대로
     * 일방통행 순환로를 태우면 67m 를 한 바퀴 돌게 된다. 짧은 후진·정렬이므로 목표를 바로
     * 준다 — 참조 구현({@code demo_loop2.py})도 이 단계만 같은 이유로 예외를 뒀다.
     */
    private void driveToExit(VehicleCycle cycle, VehicleMotion motion, long now) {
        cycle.setTarget("EXIT");
        CycleProperties.Station exit = cycleProperties.exit();

        if (distance(motion, exit.x(), exit.y()) <= cycleProperties.arriveTolM()) {
            releaseBay(cycle.vehicleId());
            cycle.advance(now);
            return;
        }
        sendGoal(cycle, motion, now, exit.x(), exit.y(), exit.yaw(), "EXIT");
    }

    // ── TO_RACK ─────────────────────────────────────────────────────────────────

    /** 배정된 랙 접근점으로 간다. 랙은 이 단계 진입 시 한 번만 고른다. */
    private void driveToRack(VehicleCycle cycle, VehicleMotion motion, long now) {
        if (cycle.rackCode() == null) {
            String rack = nextRack(cycle);
            if (rack == null) {
                // 배정표가 비어 있다. 목표 없이 서 있는 것보다 순환을 유지하며 다음 tick 을 기다린다.
                log.warn("랙 배정표가 비어 있어 주기를 진행할 수 없다: vehicleId={}", cycle.vehicleId());
                driveLoop(cycle, motion, now);
                return;
            }
            cycle.assignRack(rack);
        }
        cycle.setTarget(cycle.rackCode());

        var approach = rackApproaches.find(cycle.rackCode()).orElse(null);
        if (approach == null) {
            // 좌표를 모르는 랙이다. 짐작해서 보내면 차량이 엉뚱한 곳으로 가므로 순환만 유지한다.
            log.warn("랙 접근점 좌표를 찾지 못했다(순환 유지): vehicleId={}, rack={}",
                    cycle.vehicleId(), cycle.rackCode());
            driveLoop(cycle, motion, now);
            return;
        }
        if (cycle.isApproaching()) {
            if (distance(motion, approach.x(), approach.y()) <= cycleProperties.arriveTolM()) {
                log.info("랙 접근점 도착: vehicleId={}, rack={}", cycle.vehicleId(), cycle.rackCode());
                cycle.advance(now);
                return;
            }
            sendGoal(cycle, motion, now,
                    approach.x(), approach.y(), approach.yawRad(), "RACK:" + cycle.rackCode());
            return;
        }

        // 규칙 1 — 랙 접근점이 가까워질 때까지는 순환로를 따라 돈다. 예전에는 여기서 곧바로
        // 접근점 좌표를 목표로 줬는데, 그러면 Nav2 가 최단 경로를 잡아 창고 한가운데를
        // 가로지른다. 통로가 아닌 곳에서 차들이 만나 서로를 막는다(2026-08-10 실측).
        if (gapToEntry(motion, approach.x(), approach.y()) > cycleProperties.approachTriggerM()) {
            driveLoop(cycle, motion, now);
            return;
        }
        cycle.markApproaching();
        sendGoal(cycle, motion, now,
                approach.x(), approach.y(), approach.yawRad(), "RACK:" + cycle.rackCode());
    }

    /**
     * DB 의 선반 코드({@code A001})를 차량이 아는 이름({@code A1})으로 바꾼다.
     *
     * <p><b>왜 필요한가.</b> 두 체계가 다르다. 백엔드·DB 는 {@code storage_slot.slot_code} 를
     * {@code A001} 형식으로 쓰고, 시뮬은 {@code RACK_SLOTS} 키를 {@code A1} 형식으로 만든다
     * ({@code cargo_demo.py:321}). 규격 §7 의 페이로드 예시도 {@code "rack": "A1"} 이다.
     *
     * <p>맞추지 않으면 시뮬이 <b>조용히 실패한다</b> — {@code if rack not in RACK_SLOTS: return False}
     * 라 예외도 응답도 없다. 차량은 화물을 든 채 가만히 있고, 백엔드는 {@code loaded} 가 안 바뀌니
     * 90 초 뒤 타임아웃으로 주기를 재시작한다. 그것이 무한 반복됐다(2026-08-10 실측).
     *
     * <p>DB 코드를 {@code A1} 로 바꾸지 않는 이유: {@code slot_code} 는 여러 도메인(적재 위치 추천·
     * 재고)이 쓰는 식별자다. 표기 차이는 <b>발행 경계에서만</b> 흡수한다.
     *
     * <p>형식이 다르면 그대로 돌려준다 — 짐작해서 바꾸면 어느 쪽도 아닌 이름이 나간다.
     */
    static String toWireRackCode(String slotCode) {
        if (slotCode == null) {
            return null;
        }
        java.util.regex.Matcher m = WIRE_RACK.matcher(slotCode);
        return m.matches() ? m.group(1) + Integer.parseInt(m.group(2)) : slotCode;
    }

    private static final java.util.regex.Pattern WIRE_RACK =
            java.util.regex.Pattern.compile("([A-Za-z]+)0*(\\d+)");

    /**
     * 스테이션 <b>진입점</b>까지 진행 방향으로 남은 호장(m).
     *
     * <p>진입점은 그 스테이션 좌표를 순환로에 투영한 지점이다 — 참조 구현
     * ({@code demo_loop2.py} 의 {@code entry_s})과 같은 정의다. 별도 좌표를 설정으로 두지
     * 않는 이유도 같다: 스테이션이 늘 때마다 진입점을 손으로 맞춰 적으면 어긋난다.
     */
    private double gapToEntry(VehicleMotion motion, double stationX, double stationY) {
        double sMe = track.project(motion.x(), motion.y()).s();
        double entryS = track.project(stationX, stationY).s();
        return track.gap(sMe, entryS);
    }

    // ── RACK ────────────────────────────────────────────────────────────────────

    /** 랙 적재. {@code loaded == false} 가 되어야 주기가 끝난다. */
    private void placeRack(VehicleCycle cycle, VehicleMotion motion, long now) {
        cycle.setTarget(cycle.rackCode());
        if (cycle.shouldSendGoal("place_rack:" + cycle.rackCode())) {
            sendPlaceRack(cycle);
        }
        if (!isLoaded(motion)) {
            log.info("주기 완료: vehicleId={}, rack={}, cycles={}",
                    cycle.vehicleId(), cycle.rackCode(), cycle.cycles() + 1);
            cycle.advance(now);
            return;
        }
        if (cycle.workTimedOut(now, cycleProperties.placeTimeoutMs())) {
            log.warn("랙 적재 시간 초과 — 주기를 재시작한다: vehicleId={}", cycle.vehicleId());
            restart(cycle, now);
        }
    }

    /**
     * 랙 적재 지시를 보낸다 — 규격 §7 의 {@code task} 와 §6 의 {@code cargo} 를 <b>둘 다</b>.
     *
     * <p>두 벌을 보내는 이유: 규격이 §6 에서는 {@code cargo ← place_rack}, §7 에서는
     * {@code task ← PLACE_RACK} 이라고 쓴다. 어느 쪽을 시뮬이 실제로 듣는지 확정되지 않아
     * 둘 다 보낸다 — 하나만 보냈다가 그쪽이 아니면 <b>차가 랙 앞에서 아무것도 안 한다.</b>
     * 중복 수신은 같은 동작을 두 번 지시하는 것이라 무해하다.
     *
     * <p>좌표를 못 찾으면 {@code cargo} 만 보낸다. 짐작한 좌표를 담아 보내면 실물이 벽으로 간다.
     */
    private void sendPlaceRack(VehicleCycle cycle) {
        String rack = cycle.rackCode();
        String wireRack = toWireRackCode(rack);
        publisher.publishCargo(cycle.vehicleId(), CargoActionMessage.placeRack(wireRack));

        var approach = rackApproaches.find(rack).orElse(null);
        Double dockX = cycleProperties.dockXFor(rack).orElse(null);
        if (approach == null || dockX == null) {
            log.warn("랙 좌표를 몰라 PLACE_RACK task 는 생략한다(cargo 만 발행): vehicleId={}, rack={}",
                    cycle.vehicleId(), rack);
            return;
        }
        publisher.publishPlaceRack(
                cycle.vehicleId(), wireRack, null,
                new PlaceRackTaskMessage.Waypoint(approach.x(), approach.y(), approach.yawRad()),
                // 도킹은 접근점과 같은 y, 랙 쪽으로 파고든 x 다.
                new PlaceRackTaskMessage.Waypoint(dockX, approach.y(), approach.yawRad()),
                approach.forkHeight(),
                cycleProperties.reverseDist());
    }

    // ── 공통 ────────────────────────────────────────────────────────────────────

    /**
     * 순환로를 따라 다음 모서리로 보낸다 (규칙 1 — 일방통행).
     *
     * <p>목표는 언제나 <b>진행 방향 다음 모서리</b>다. 역주행 목표를 주지 않는다.
     */
    private void driveLoop(VehicleCycle cycle, VehicleMotion motion, long now) {
        double s = track.project(motion.x(), motion.y()).s();
        int cornerIndex = track.nextCornerIndex(s, loopProperties.cornerTolM());
        Track.Pose corner = track.cornerPose(cornerIndex);
        sendGoal(cycle, motion, now,
                corner.x(), corner.y(), Math.toRadians(corner.headingDeg()), "C" + cornerIndex);
    }

    /**
     * 목표를 보낸다. 같은 목표는 다시 보내지 않되, <b>정체가 감지되면 다시 보낸다</b>
     * (규칙 4 — Nav2 목표가 조용히 실패하면 아무 일도 일어나지 않는다).
     */
    private void sendGoal(
            VehicleCycle cycle, VehicleMotion motion, long now,
            double x, double y, double yawRad, String goalKey) {

        boolean stalled = cycle.updateAndCheckStall(
                motion.x(), motion.y(), now,
                loopProperties.stallMoveM(), loopProperties.stallSec() * 1000L);
        if (stalled) {
            log.warn("정체 감지 — 목표를 재전송한다: vehicleId={}, goal={}", cycle.vehicleId(), goalKey);
            cycle.forgetGoal();
            // 진입하다 막힌 것일 수 있다. 순환로 주행부터 다시 판단하게 되돌린다 —
            // 같은 목표만 계속 다시 쏘면 막힌 자리에서 못 벗어난다.
            cycle.cancelApproach();
        }
        if (cycle.shouldSendGoal(goalKey) && !publisher.publishGoal(cycle.vehicleId(), x, y, yawRad)) {
            // 발행에 실패했으면 "보냈다"는 기억을 지운다 — 다음 tick 에 다시 시도된다.
            cycle.forgetGoal();
        }
    }

    /** 주기를 처음부터 다시. 실패 복구용이라 주기 수는 올리지 않는다. */
    private void restart(VehicleCycle cycle, long now) {
        cycle.restartToBay(now);
    }

    private void releaseBay(String vehicleId) {
        if (vehicleId.equals(bayOwner)) {
            bayOwner = null;
        }
    }

    /** 차량별 배정표에서 다음 랙을 고른다. 목록을 다 쓰면 처음으로 돌아간다. */
    private String nextRack(VehicleCycle cycle) {
        List<String> racks = cycleProperties.racksFor(cycle.vehicleId());
        if (racks.isEmpty()) {
            return null;
        }
        return racks.get(cycle.rackIndex() % racks.size());
    }

    private VehicleCycle cycleOf(String vehicleId, long now) {
        return cycles.computeIfAbsent(vehicleId, id -> new VehicleCycle(id, now));
    }

    /**
     * 지금 화물을 싣고 있는가.
     *
     * <p><b>차량이 보고한 적재 여부를 먼저 본다.</b> 예전에는 {@code status} 로만 판정했는데,
     * 시뮬은 적재를 마친 뒤에도 {@code IDLE} 을 보고한다. 그래서 실제로는 화물(C0009)을 싣고
     * 있는데 {@link #load}) 가 그것을 못 알아보고 40 초 뒤 타임아웃 → 주기 재시작을 무한
     * 반복했다. 랙에는 한 번도 못 가면서 완료 주기 수만 올라갔다(2026-08-10 실측 8주기).
     *
     * <p>{@code null} 은 "모른다"다 — 이 필드를 안 보내는 차량(실물)은 예전처럼 상태로 본다.
     * {@code UNLOADING} 은 "내려놓는 중"이므로 아직 싣고 있다는 뜻이다.
     */
    private static boolean isLoaded(VehicleMotion motion) {
        Boolean reported = motion.loaded();
        if (reported != null) {
            return reported;
        }
        return motion.status() == VehicleStatus.LOADING
                || motion.status() == VehicleStatus.UNLOADING;
    }

    private static double distance(VehicleMotion motion, double x, double y) {
        return Math.hypot(motion.x() - x, motion.y() - y);
    }

    /** 이 차량의 현재 주기 상태. 아직 주기를 시작하지 않았으면 {@code null}. */
    public VehicleCycle cycleFor(String vehicleId) {
        return cycles.get(vehicleId);
    }

    /** 진단·화면용 스냅샷. */
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> perVehicle = new LinkedHashMap<>();
        cycles.forEach((id, cycle) -> perVehicle.put(id, Map.of(
                "phase", cycle.phase(),
                "target", String.valueOf(cycle.target()),
                "rack", String.valueOf(cycle.rackCode()),
                "cycles", cycle.cycles())));
        map.put("bayOwner", String.valueOf(bayOwner));
        map.put("vehicles", perVehicle);
        return map;
    }

    /** 운행 종료 시 초기화. */
    public void reset() {
        cycles.clear();
        arrivedSignals.clear();
        procedureRegistry.clear();
        bayOwner = null;
    }
}
