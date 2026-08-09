package com.fast.backend.traffic.service;

import com.fast.backend.traffic.config.CycleProperties;
import com.fast.backend.traffic.config.LoopTrackProperties;
import com.fast.backend.traffic.domain.Track;
import com.fast.backend.traffic.domain.VehicleCycle;
import com.fast.backend.traffic.domain.VehicleMotion;
import com.fast.backend.traffic.dto.CargoActionMessage;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.placement.PlacementCandidate;
import com.fast.backend.storage.placement.PlacementRecommendation;
import com.fast.backend.storage.placement.PlacementService;
import com.fast.backend.storage.mapper.StorageSlotMapper;
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
    private final StorageSlotMapper storageSlotMapper;
    private final CargoMapper cargoMapper;
    private final PlacementService placementService;

    /** 등록된 칸 수. 실행 중에 늘지 않으므로 한 번만 읽는다. {@code -1} 은 아직 안 읽었다는 뜻. */
    private volatile int totalSlots = -1;

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
            VehicleProcedureRegistry procedureRegistry,
            StorageSlotMapper storageSlotMapper,
            CargoMapper cargoMapper,
            PlacementService placementService) {
        this.cycleProperties = cycleProperties;
        this.loopProperties = loopProperties;
        this.track = loopProperties.toTrack();
        this.operationService = operationService;
        this.publisher = publisher;
        this.rackApproaches = rackApproaches;
        this.procedureRegistry = procedureRegistry;
        this.storageSlotMapper = storageSlotMapper;
        this.cargoMapper = cargoMapper;
        this.placementService = placementService;
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
        // 랙이 다 찼는지는 tick 당 한 번만 본다 — 차량마다 물으면 같은 답을 여러 번 읽는다.
        boolean full = racksFull();

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
            // 놓을 자리가 없으면 하던 단계를 접고 시작 위치로 돌아간다. 화물을 든 채여도
            // 마찬가지다 — 어차피 내려놓을 칸이 없고, 통로에 세워 두면 다음 운행을 막는다.
            if (full && !cycle.phase().isHomebound()) {
                log.info("랙이 가득 찼다 — 복귀를 시작한다: vehicleId={}, 완료 주기={}",
                        vehicleId, cycle.cycles());
                cycle.startReturning(now);
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
            case RETURNING -> driveHome(cycle, motion, now);
            case PARKED -> { }      // 도착했다. 더 보낼 것이 없다.
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
            String rack = nextRack(cycle, motion);
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
        // 0층(바닥)이 먼저다 — 아래 SHELF 패턴은 "AF01" 도 삼켜 "AF1" 을 만든다.
        java.util.regex.Matcher floor = FLOOR_RACK.matcher(slotCode);
        if (floor.matches()) {
            return floor.group(1) + Integer.parseInt(floor.group(2)) + "F";
        }
        java.util.regex.Matcher m = SHELF_RACK.matcher(slotCode);
        return m.matches() ? m.group(1) + Integer.parseInt(m.group(2)) : slotCode;
    }

    /** 0층(바닥) — {@code AF01} → {@code A1F}. */
    private static final java.util.regex.Pattern FLOOR_RACK =
            java.util.regex.Pattern.compile("([A-Za-z]+)F0*(\\d+)");

    /** 1층(선반) — {@code A001} → {@code A1}. 지금 시뮬이 아는 유일한 형식이다. */
    private static final java.util.regex.Pattern SHELF_RACK =
            java.util.regex.Pattern.compile("([A-Za-z]+)0*(\\d+)");

    // ── RETURNING ───────────────────────────────────────────────────────────────

    /**
     * 시작 위치로 돌아간다. 규칙 1 을 그대로 지킨다 — 복귀도 순환로를 따라 간다.
     *
     * <p>복귀 지점이 설정에 없으면 <b>그 자리에 세운다.</b> 짐작한 좌표로 보내느니 서 있는
     * 편이 낫다 — 통로 한가운데로 보내면 다음 운행이 시작부터 막힌다.
     */
    private void driveHome(VehicleCycle cycle, VehicleMotion motion, long now) {
        cycle.setTarget("HOME");
        CycleProperties.Station home = cycleProperties.homeFor(cycle.vehicleId()).orElse(null);
        if (home == null) {
            log.warn("복귀 지점이 설정에 없어 그 자리에 멈춘다(traffic.cycle.home 확인): vehicleId={}",
                    cycle.vehicleId());
            cycle.advance(now);     // → PARKED
            return;
        }

        if (cycle.isApproaching()) {
            if (distance(motion, home.x(), home.y()) <= cycleProperties.arriveTolM()) {
                log.info("복귀 완료: vehicleId={}, 완료 주기={}", cycle.vehicleId(), cycle.cycles());
                cycle.advance(now); // → PARKED
                return;
            }
            sendGoal(cycle, motion, now, home.x(), home.y(), home.yaw(), "HOME");
            return;
        }
        if (gapToEntry(motion, home.x(), home.y()) > cycleProperties.approachTriggerM()) {
            driveLoop(cycle, motion, now);
            return;
        }
        cycle.markApproaching();
        sendGoal(cycle, motion, now, home.x(), home.y(), home.yaw(), "HOME");
    }

    /**
     * 랙이 가득 찼는가 — 복귀 조건.
     *
     * <p><b>칸이 하나도 등록돼 있지 않으면 "가득 찼다"고 하지 않는다.</b> {@code COUNT(EMPTY)=0}
     * 만 보면 시드를 넣지 않은 환경에서 시작하자마자 전 차량이 복귀해 버린다. 등록 수는 실행 중에
     * 늘지 않으므로 한 번만 읽는다.
     *
     * <p>조회에 실패하면 {@code false} 다 — 모르는 상태에서 운행을 접는 쪽으로 기울지 않는다.
     */
    private boolean racksFull() {
        try {
            if (totalSlots < 0) {
                totalSlots = storageSlotMapper.countAll();
            }
            return totalSlots > 0 && storageSlotMapper.countEmpty() == 0;
        } catch (RuntimeException e) {
            log.error("적재 칸 조회 실패(복귀 판단 보류): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 놓은 칸을 {@code OCCUPIED} 로 표시한다.
     *
     * <p>이걸 하지 않으면 랙이 영원히 비어 있는 것으로 보여 복귀 조건이 성립하지 않는다.
     * 예전에는 주기 상태기계가 {@code storage_slot} 을 전혀 건드리지 않았다.
     *
     * <p>실패해도 주기를 멈추지 않는다 — 기록이 안 됐다고 차를 세울 이유는 없다. 대신 남긴다.
     */
    private void markSlotOccupied(String slotCode) {
        if (slotCode == null) {
            return;
        }
        try {
            // OCCUPIED 는 화물 ID 를 요구한다(chk_storage_slot_state). 주기에는 운반 작업이
            // 없어 줄 ID 가 없으므로 여기서 하나 만든다 — 박스가 실제로 그 칸에 놓였으니
            // 사실이고, cargo 테이블은 id 와 시각뿐이라 만드는 비용도 없다.
            Cargo cargo = new Cargo();
            cargo.setCreatedAt(CommunicationTime.nowLocal());
            cargoMapper.insert(cargo);
            if (storageSlotMapper.markOccupiedIfEmpty(slotCode, cargo.getCargoId()) == 0) {
                // 이미 OCCUPIED 거나 다른 흐름이 예약한 칸이다. 덮어쓰지 않는다.
                // 방금 만든 화물은 주인 없이 남는다 — 주기 데모용이라 그대로 둔다.
                log.warn("적재 칸 상태를 바꾸지 못했다(이미 비어 있지 않음): slotCode={}, cargoId={}",
                        slotCode, cargo.getCargoId());
            }
        } catch (RuntimeException e) {
            log.error("적재 칸 상태 기록 실패(격리됨): slotCode={}, error={}", slotCode, e.getMessage());
        }
    }

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
            markSlotOccupied(cycle.rackCode());
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

    /**
     * 다음에 놓을 랙을 고른다.
     *
     * <p><b>화물 높이로 층을 고르는 것이 우선</b>이고({@code placement-enabled}), 그것이 안 되면
     * 차량별 배정표 순서로 돌아간다. 폴백을 남겨 두는 이유: 높이를 모르는 차량(그 필드를 안 보내는
     * 실물)이나 DB 조회 실패에도 주기는 계속 돌아야 한다. 배정표가 없으면 {@code null} 이고,
     * 호출부가 순환만 유지한다.
     */
    private String nextRack(VehicleCycle cycle, VehicleMotion motion) {
        String byHeight = recommendByHeight(cycle, motion);
        if (byHeight != null) {
            return byHeight;
        }
        List<String> racks = cycleProperties.racksFor(cycle.vehicleId());
        if (racks.isEmpty()) {
            return null;
        }
        return racks.get(cycle.rackIndex() % racks.size());
    }

    /**
     * 화물 높이에 맞는 칸을 고른다. 못 고르면 {@code null}(호출부가 배정표로 넘어간다).
     *
     * <p><b>단위에 주의.</b> telemetry 의 화물 높이는 <b>시뮬 단위</b>이고 <b>팔레트가 포함</b>돼
     * 있다. {@code usable_height} 는 실물 m 라 {@code cargoHeightScale} 로 환산해서 비교하고,
     * 팔레트를 두 번 더하지 않도록 {@code recommendByTotalHeight} 로 넣는다.
     *
     * <p><b>다른 차가 향하고 있는 칸은 뺀다.</b> 두 대가 같은 칸을 목표로 잡으면 뒤에 도착한 쪽이
     * 이미 찬 자리에 놓는다 — 그때는 {@code markOccupiedIfEmpty} 가 0 을 돌려주지만 박스는 이미
     * 놓인 뒤라 되돌릴 수 없다.
     */
    private String recommendByHeight(VehicleCycle cycle, VehicleMotion motion) {
        if (!Boolean.TRUE.equals(cycleProperties.placementEnabled())) {
            return null;
        }
        Double simHeight = motion.cargoHeight();
        if (simHeight == null || simHeight <= 0) {
            log.debug("화물 높이를 몰라 배정표로 고른다: vehicleId={}", cycle.vehicleId());
            return null;
        }
        double totalHeight = simHeight * cycleProperties.cargoHeightScale();
        try {
            java.util.Set<String> taken = slotsOtherVehiclesAreHeadingTo(cycle.vehicleId());
            List<PlacementCandidate> candidates = new java.util.ArrayList<>();
            for (var row : storageSlotMapper.findAllEmptySlotsForPlacement()) {
                if (taken.contains(row.getSlotCode())) {
                    continue;
                }
                candidates.add(new PlacementCandidate(
                        row.getSlotCode(), row.getUsableHeight(), row.getUsableWidth(),
                        row.getForkHeight(), row.getDestinationX(), row.getDestinationY(),
                        row.getDestinationHeading(), null, row.getStatus()));
            }
            PlacementRecommendation pick =
                    placementService.recommendByTotalHeight(totalHeight, candidates);
            log.info("높이로 랙 선택: vehicleId={}, 화물높이={}(시뮬)={}m, rack={}, fork={}",
                    cycle.vehicleId(), simHeight, totalHeight, pick.slotCode(), pick.forkHeight());
            return pick.slotCode();
        } catch (RuntimeException e) {
            log.warn("높이로 랙을 고르지 못해 배정표로 간다: vehicleId={}, 사유={}",
                    cycle.vehicleId(), e.getMessage());
            return null;
        }
    }

    /** 지금 다른 차가 향하고 있는 칸. 같은 자리를 두 대가 노리지 않게 한다. */
    private java.util.Set<String> slotsOtherVehiclesAreHeadingTo(String vehicleId) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        cycles.forEach((id, other) -> {
            if (!id.equals(vehicleId) && other.rackCode() != null) {
                taken.add(other.rackCode());
            }
        });
        return taken;
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
