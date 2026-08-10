package com.fast.backend.traffic.service;

import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.traffic.config.LoopTrackProperties;
import com.fast.backend.traffic.config.TrafficControlProperties;
import com.fast.backend.traffic.domain.CollisionPredictor;
import com.fast.backend.traffic.domain.OperationState;
import com.fast.backend.traffic.domain.Track;
import com.fast.backend.traffic.domain.TrafficControlEvent;
import com.fast.backend.traffic.domain.TrafficStopDecision;
import com.fast.backend.traffic.mapper.TrafficControlEventMapper;
import com.fast.backend.traffic.domain.VehicleMotion;
import com.fast.backend.traffic.domain.WorkZone;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.service.VehicleCommandService;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 차량 경로 충돌 예측 및 안전거리 제어 (FR-502-1a).
 *
 * <p>흐름: ROS2/Isaac → MQTT 위치·상태 → <b>이 서비스가 메모리에서 판단</b> → MQTT STOP/RESUME.
 *
 * <pre>
 *   tick 마다
 *     1. 최신 위치 수집 → 낡은 것 제외 → 제어 대상만 남김
 *     2. 작업 구역 점유 갱신 (WorkZoneRegistry)
 *     3. 차량별 정지 사유 판정 (CollisionPredictor)
 *     4. 상태가 바뀐 차량에만 STOP / RESUME 발행
 * </pre>
 *
 * <p><b>명령은 상태가 바뀔 때만 보낸다.</b> 매 tick 발행하면 브로커와 차량이 같은 명령을 초당 두 번씩
 * 받게 되고, 로그에서 "무엇 때문에 언제 멈췄는지"를 읽을 수 없게 된다.
 *
 * <p><b>안전 원칙</b>
 * <ul>
 *   <li>기능이 꺼져 있거나 제어 대상 목록이 비면 <b>아무 명령도 보내지 않는다</b></li>
 *   <li>위치가 낡은 차량은 판단에서 제외한다 — 낡은 좌표로 "안전"을 선언하는 것이 가장 위험하다.
 *       <b>세워 둔 차량은 자기 위치가 낡아도, 자기를 세운 상대의 위치가 낡아도 풀지 않는다</b>
 *       (양쪽 모두 "모르는 상태에서 재개시키지 않는다" 다 — {@link #counterpartVisible})</li>
 *   <li><b>전체 운행이 멈춰 있으면(PAUSED·ESTOPPED) 재개하지 않는다.</b> 정지는 어느 상태에서든
 *       내보내되, 사람이 세워 둔 것을 기계가 푸는 방향으로는 절대 움직이지 않는다</li>
 *   <li>정지는 즉시, 재개는 여유 거리({@code releaseDistanceM})에서 — 경계 채터링 방지</li>
 * </ul>
 */
@Service
public class TrafficControlService {

    private static final Logger log = LoggerFactory.getLogger(TrafficControlService.class);

    private final TrafficControlProperties properties;
    private final LoopTrackProperties loopProperties;
    /** 순환로 기하. 불변이라 tick 마다 다시 만들지 않는다. */
    private final Track track;
    private final OperationService operationService;
    private final CycleControlService cycleControlService;
    private final LatestVehicleLocationProvider locationProvider;
    private final VehicleCurrentStatusMapper statusMapper;
    private final WorkZoneProvider workZoneRegistry;
    private final VehicleCommandService commandService;
    private final TrafficControlEventMapper eventMapper;

    /** 관제가 세워 둔 차량 → 정지 판단. 여기 있으면 STOP 을 이미 보낸 것이다. */
    private final Map<String, TrafficStopDecision> heldVehicles = new ConcurrentHashMap<>();

    public TrafficControlService(
            TrafficControlProperties properties,
            LoopTrackProperties loopProperties,
            OperationService operationService,
            CycleControlService cycleControlService,
            LatestVehicleLocationProvider locationProvider,
            VehicleCurrentStatusMapper statusMapper,
            WorkZoneProvider workZoneRegistry,
            VehicleCommandService commandService,
            TrafficControlEventMapper eventMapper) {
        this.properties = properties;
        this.loopProperties = loopProperties;
        this.track = loopProperties.toTrack();
        this.operationService = operationService;
        this.cycleControlService = cycleControlService;
        this.locationProvider = locationProvider;
        this.statusMapper = statusMapper;
        this.workZoneRegistry = workZoneRegistry;
        this.commandService = commandService;
        this.eventMapper = eventMapper;
    }

    /** 한 tick. 예외를 밖으로 내보내지 않는다 — 한 번 실패가 스케줄러를 멈추면 관제가 조용히 죽는다. */
    public void tick() {
        try {
            evaluate();
        } catch (RuntimeException e) {
            log.error("교통 관제 tick 실패(격리됨): {}", e.getMessage(), e);
        }
    }

    private void evaluate() {
        if (!properties.enabled() || properties.vehicles().isEmpty()) {
            return;
        }

        List<VehicleMotion> motions = collectFreshMotions();
        workZoneRegistry.refresh(motions);
        List<WorkZone> zones = workZoneRegistry.zones();

        releaseOneIntoLoop(motions);

        for (VehicleMotion motion : motions) {
            if (!properties.controls(motion.vehicleId())) {
                continue;   // 제어 대상이 아닌 차량은 관측만 하고 명령하지 않는다
            }
            // 규칙 5 — 사람이 직접 세워 둔 차량은 관제가 건드리지 않는다.
            // 여기서 RESUME 을 내보내면 "정지 버튼을 눌렀는데 다시 움직이는" 상황이 된다.
            if (operationService.isManuallyHeld(motion.vehicleId())) {
                continue;
            }
            Optional<TrafficStopDecision> decision = findStopReason(motion, motions, zones);
            if (decision.isPresent()) {
                hold(motion.vehicleId(), decision.get());
            } else if (heldVehicles.containsKey(motion.vehicleId())
                    && canRelease(motion, motions, zones)) {
                release(motion.vehicleId());
            }
        }

        // 차간 판정이 끝난 뒤에 주기를 진행시킨다 — 세워야 할 차를 먼저 세우고,
        // 그 결과(heldVehicles)를 넘겨 "지금 서 있는 차에는 목표를 주지 않게" 한다.
        cycleControlService.tick(motions, heldVehicles.keySet());
    }

    /**
     * 규칙 0 — 한 tick 에 한 대씩 순환로에 합류시킨다.
     *
     * <p>앞차와의 간격 계산만 여기서 하고, 순서·쿨다운 판단은 {@link OperationService} 가 한다.
     * 순환로 기하를 아는 쪽과 운행 상태를 아는 쪽을 나눠 두려는 것이다.
     */
    private void releaseOneIntoLoop(List<VehicleMotion> motions) {
        if (!operationService.state().isDriving()) {
            return;
        }
        Map<String, VehicleMotion> byId = new LinkedHashMap<>();
        for (VehicleMotion motion : motions) {
            byId.put(motion.vehicleId(), motion);
        }
        operationService.releaseOne(
                properties.tickMs(),
                loopProperties.entryHeadwayM(),
                loopProperties.entryIntervalMs(),
                candidateId -> nearestJoinedGap(candidateId, byId));
    }

    /**
     * 합류 후보 앞쪽에 있는, <b>이미 합류한</b> 차량까지의 최단 거리. 앞이 비어 있으면 {@code null}.
     *
     * <p>순환로를 벗어난 차량은 세지 않는다 — 바이에서 작업 중인 차 때문에 새 차가 영영 합류하지
     * 못하면 운행이 시작되지 않는다.
     */
    private Double nearestJoinedGap(String candidateId, Map<String, VehicleMotion> byId) {
        VehicleMotion candidate = byId.get(candidateId);
        if (candidate == null) {
            return null;    // 위치를 모르는 차량 — 간격으로 막지 않는다(온라인 판정이 이미 걸렀다)
        }
        double sMe = track.project(candidate.x(), candidate.y()).s();
        Double nearest = null;
        for (VehicleMotion other : byId.values()) {
            if (other.vehicleId().equals(candidateId) || !operationService.isJoined(other.vehicleId())) {
                continue;
            }
            if (!CollisionPredictor.isOnTrack(track, other, loopProperties.offTrackTolM())) {
                continue;
            }
            double gap = track.gap(sMe, track.project(other.x(), other.y()).s());
            if (nearest == null || gap < nearest) {
                nearest = gap;
            }
        }
        return nearest;
    }

    /**
     * 이 차량을 세워야 하는 이유. 없으면 빈 값.
     *
     * <p>FR 의 두 조건을 그대로 옮긴 것이다 — (1) 예상 거리 &lt; 최소 안전거리, (2) 예상 경로가
     * 선반 작업 구역과 겹침. <b>둘 중 하나만 만족해도</b> 정지다.
     */
    private Optional<TrafficStopDecision> findStopReason(
            VehicleMotion target, List<VehicleMotion> all, List<WorkZone> zones) {

        Optional<WorkZone> blocking =
                CollisionPredictor.firstBlockingZone(target, zones, properties.predictionHorizonS());
        if (blocking.isPresent()) {
            return Optional.of(TrafficStopDecision.workZone(
                    blocking.get().slotCode(), blocking.get().occupiedBy()));
        }

        boolean targetOnTrack = CollisionPredictor.isOnTrack(track, target, loopProperties.offTrackTolM());

        for (VehicleMotion other : all) {
            if (other.vehicleId().equals(target.vehicleId())) {
                continue;
            }
            Double gap = gapTo(target, other, targetOnTrack);
            if (gap == null || gap >= blockingDistance(other)) {
                continue;
            }
            // 거리는 가깝다. 이제 "둘 중 누가 비켜야 하는가"를 정한다.
            Optional<String> whoStops = CollisionPredictor.resolveWhoStops(target, other);
            boolean targetMustStop = whoStops
                    .map(id -> id.equals(target.vehicleId()))
                    // 정할 수 없는 경우(마주 달림 등)는 양쪽 다 세운다 — 안전 쪽으로 기운다.
                    .orElse(true);
            if (targetMustStop) {
                return Optional.of(TrafficStopDecision.safetyDistance(other.vehicleId(), gap));
            }
        }
        return Optional.empty();
    }

    /**
     * 이 앞차 때문에 멈춰야 하는 거리 (규칙 2 — 두 단계).
     *
     * <p>규격이 거리를 둘로 나눈 이유가 있다.
     * <ul>
     *   <li><b>서 있는 차</b>(작업 중·관제 정지·대기) → {@code holdDistanceM}(9.0).
     *       비켜 줄 리가 없으니 멀리서부터 멈춰야 한다</li>
     *   <li><b>움직이는 차</b> → {@code safeDistanceM}(6.0). 앞차도 가고 있으므로
     *       9.0 에서 멈추면 <b>줄줄이 서서 순환이 죽는다</b></li>
     * </ul>
     *
     * <p>한 값으로 통일하면 둘 중 하나가 어긋난다 — 9.0 이면 흐름이 막히고, 6.0 이면
     * 하역 중인 차에 너무 가까이 붙는다.
     */
    private double blockingDistance(VehicleMotion other) {
        return isStopped(other) ? properties.holdDistanceM() : properties.safeDistanceM();
    }

    /** 앞차가 <b>비켜 줄 수 없는 상태</b>인가. 규격 §4 {@code stoppedKind} 와 같은 판정이다. */
    private boolean isStopped(VehicleMotion other) {
        return other.isWorking()                                    // LOADING / UNLOADING / LIFTING
                || other.isHeld()                                   // HOLDING
                || other.status() == VehicleStatus.ESTOP            // 비상정지
                || heldVehicles.containsKey(other.vehicleId())      // 관제가 세워 둔 차
                || operationService.isManuallyHeld(other.vehicleId());
    }

    /**
     * 두 차량 사이 판단 거리. 순환로 위에 둘 다 있으면 <b>호장</b>, 아니면 직선.
     *
     * <p><b>왜 나누는가.</b> 창고 가운데가 랙이라 좌우 통로가 갈라져 있어서, 반대편 통로의
     * 차량은 직선으로 10.5 지만 실제 주행 거리는 반 바퀴(약 33)다. 직선으로 재면 그 차를
     * "바로 앞차"로 보고 엉뚱하게 세운다(F팀 규격 §10 함정 1번).
     *
     * <p>반대로 <b>바이나 랙으로 빠진 차량은 순환로 위에 없다.</b> 그런 차량에 호장을 쓰면
     * 투영이 엉뚱한 지점으로 떨어져 실제보다 멀거나 가깝게 나온다. 그래서 한쪽이라도 이탈했으면
     * 직선으로 돌아간다 — 정확하진 않아도 <b>안전 쪽으로 기운다</b>(직선은 항상 호장 이하다).
     *
     * @return 판단 거리. 상대가 이탈해 있고 나는 순환로 위라면 {@code null}(판단에서 제외)
     */
    private Double gapTo(VehicleMotion target, VehicleMotion other, boolean targetOnTrack) {
        boolean otherOnTrack = CollisionPredictor.isOnTrack(track, other, loopProperties.offTrackTolM());
        if (targetOnTrack && otherOnTrack) {
            return CollisionPredictor.effectiveTrackGap(
                    track, target, other, properties.predictionHorizonS());
        }
        if (targetOnTrack) {
            // 나는 통로에 있고 상대는 빠져 있다 — 내 진로를 막지 않으므로 세울 이유가 없다.
            // 다만 작업 구역 판정(firstBlockingZone)은 위에서 이미 했으므로 놓치지 않는다.
            return null;
        }
        return CollisionPredictor.effectiveGap(target, other, properties.predictionHorizonS());
    }

    /**
     * 지금 이 차량을 풀어도 되는가.
     *
     * <p>세 관문을 <b>모두</b> 통과해야 한다. 하나라도 모르면 세워 둔 채로 남긴다 — 재개는
     * 서두를 이유가 없고, 잘못 푼 것은 되돌릴 수 없다.
     */
    private boolean canRelease(
            VehicleMotion target, List<VehicleMotion> all, List<WorkZone> zones) {

        // (1) 전체 운행이 멈춰 있으면 관제도 풀지 않는다. 일시정지·비상정지를 눌러 둔 채로
        //     차간 거리가 벌어졌다는 이유만으로 RESUME 이 나가면, 사람이 세운 것을 기계가 푼다.
        if (!operationService.state().isDriving()) {
            return false;
        }
        // (2) 세운 이유가 됐던 상대가 지금 안 보이면 풀지 않는다.
        if (!counterpartVisible(target.vehicleId(), all)) {
            return false;
        }
        return isSafeToRelease(target, all, zones);
    }

    /**
     * 세운 사유의 상대 차량이 아직 판단 목록에 남아 있는가.
     *
     * <p><b>왜 필요한가.</b> 낡은 위치는 {@link #collectFreshMotions} 에서 목록째 빠진다. 그러면
     * {@link #isSafeToRelease} 는 그 차량과의 거리를 아예 재지 않고 "위반 없음 = 안전"으로 읽는다.
     * 즉 <b>앞차의 telemetry 가 끊기는 것만으로 뒤차가 풀린다.</b> 앞차가 어디 있는지 모르는데
     * 그쪽으로 다시 보내는 셈이라, 이 클래스가 내건 "모르는 상태에서 재개시키지 않는다"와 정반대다.
     *
     * <p>상대가 없는 사유(작업 구역 등)면 이 관문은 통과시킨다 — 볼 대상이 없다.
     */
    private boolean counterpartVisible(String vehicleId, List<VehicleMotion> all) {
        TrafficStopDecision decision = heldVehicles.get(vehicleId);
        String counterpart = decision == null ? null : decision.counterpartVehicleId();
        if (counterpart == null) {
            return true;
        }
        for (VehicleMotion motion : all) {
            if (motion.vehicleId().equals(counterpart)) {
                return true;
            }
        }
        log.debug("재개 보류(상대 차량 위치를 모름): vehicleId={}, 상대={}", vehicleId, counterpart);
        return false;
    }

    /**
     * 재개해도 되는가 — FR 의 재개 조건 두 가지가 <b>모두</b> 만족해야 한다.
     * (선행 차량이 구역을 이탈했고, 현재·예상 거리가 안전거리 이상)
     *
     * <p>정지 임계가 아니라 {@link TrafficControlProperties#releaseDistanceM()} 를 쓰는 것이 핵심이다.
     * 같은 값으로 판단하면 경계에서 STOP/RESUME 이 매 tick 번갈아 나간다.
     */
    private boolean isSafeToRelease(
            VehicleMotion target, List<VehicleMotion> all, List<WorkZone> zones) {

        if (CollisionPredictor.firstBlockingZone(target, zones, properties.predictionHorizonS())
                .isPresent()) {
            return false;
        }
        double release = properties.releaseDistanceM();
        boolean targetOnTrack = CollisionPredictor.isOnTrack(track, target, loopProperties.offTrackTolM());
        for (VehicleMotion other : all) {
            if (other.vehicleId().equals(target.vehicleId())) {
                continue;
            }
            Double gap = gapTo(target, other, targetOnTrack);
            if (gap != null && gap < release) {
                return false;
            }
        }
        return true;
    }

    /**
     * 정지시킨다. 이미 <b>같은 사유로</b> 세워 둔 차량이면 아무 것도 하지 않는다.
     *
     * <p>사유가 바뀌었다면(안전거리 → 작업 구역 등) 명령은 다시 보내지 않되 <b>이벤트는 남긴다</b> —
     * 차량은 이미 서 있으므로 STOP 을 또 보낼 이유가 없지만, "무엇 때문에 계속 서 있는지"가 바뀐 것은
     * 나중에 되짚을 때 필요한 정보다.
     */
    private void hold(String vehicleId, TrafficStopDecision decision) {
        TrafficStopDecision previous = heldVehicles.get(vehicleId);
        if (decision.sameAs(previous)) {
            return;     // 같은 상황이 계속되는 중 — 명령도 기록도 반복하지 않는다
        }
        boolean alreadyHeld = previous != null;
        heldVehicles.put(vehicleId, decision);
        log.warn("교통 관제 정지: vehicleId={}, 사유={}({})",
                vehicleId, decision.reason(), decision.detail());

        String commandId = alreadyHeld ? null : issue(vehicleId, "STOP");
        record(TrafficControlEvent.hold(vehicleId, decision, CommunicationTime.nowLocal()), commandId);
    }

    private void release(String vehicleId) {
        TrafficStopDecision previous = heldVehicles.remove(vehicleId);
        log.info("교통 관제 재개: vehicleId={}, 이전 정지 사유={}",
                vehicleId, previous == null ? null : previous.detail());

        String commandId = issue(vehicleId, "RESUME");
        record(TrafficControlEvent.release(vehicleId, previous, CommunicationTime.nowLocal()), commandId);
    }

    /**
     * 이벤트를 DB 에 남긴다.
     *
     * <p><b>실패해도 관제를 멈추지 않는다.</b> 기록은 사후 분석용이고, 그것 때문에 차량 제어가
     * 중단되면 본말이 뒤바뀐다. 대신 실패 사실은 ERROR 로 남겨 기록 유실을 눈치챌 수 있게 한다.
     */
    private void record(TrafficControlEvent event, String commandId) {
        try {
            event.setCommandId(commandId);
            eventMapper.insert(event);
        } catch (RuntimeException e) {
            log.error("교통 관제 이벤트 기록 실패(격리됨): vehicleId={}, type={}, error={}",
                    event.getVehicleId(), event.getEventType(), e.getMessage());
        }
    }

    /**
     * 명령 발행. 실패해도 예외를 올리지 않는다 — 한 차량 발행 실패가 나머지 차량 판단을 막으면
     * 전체 관제가 멈춘다.
     *
     * <p>발행 실패 시 {@code heldVehicles} 를 되돌리지 않는 이유: STOP 이 실패했다면 차량은 여전히
     * 움직이는 중이고, 기록만 지우면 다음 tick 에 <b>또 STOP 을 보낸다</b>(재시도). 반대로 RESUME 이
     * 실패했다면 차량은 멈춘 채인데 기록은 이미 지워졌으므로, 다음 tick 에 위험이 남아 있으면 다시
     * STOP 이 나간다. 어느 쪽도 위험 방향으로 굳지 않는다.
     */
    private String issue(String vehicleId, String command) {
        try {
            VehicleCommandResponse response = commandService.issueCommand(vehicleId,
                    new VehicleCommandRequest(command, null, null, null, null));
            return response == null ? null : response.commandId();
        } catch (RuntimeException e) {
            log.error("교통 관제 명령 발행 실패: vehicleId={}, command={}, error={}",
                    vehicleId, command, e.getMessage());
            return null;
        }
    }

    /**
     * 최신 위치 중 <b>신선한 것만</b> 추린다.
     *
     * <p>낡은 좌표를 그대로 쓰면 이미 움직인 차량을 제자리에 있다고 보고 "안전"을 선언하게 된다.
     * 그래서 오래된 차량은 목록에서 뺀다 — 그러면 그 차량과의 거리 판정 자체가 일어나지 않고,
     * 세워 둔 차량 <b>자신</b>이 낡았다면 순회 대상에서 빠져 세워진 채로 남는다.
     *
     * <p><b>목록에서 빼는 것만으로는 부족하다.</b> 빠진 것이 <b>상대</b> 차량이면 거리 위반이
     * 사라져 오히려 재개 쪽으로 기운다. 그래서 {@link #counterpartVisible} 이 "나를 세운 상대가
     * 아직 보이는가"를 따로 확인한다. 여기서 거르는 것과 거기서 거르는 것은 방향이 다르다.
     */
    private List<VehicleMotion> collectFreshMotions() {
        OffsetDateTime now = CommunicationTime.nowOffset();
        List<VehicleLocationSnapshot> fresh = new ArrayList<>();
        for (VehicleLocationSnapshot snapshot : locationProvider.findAllLatest()) {
            if (snapshot.x() == null || snapshot.y() == null) {
                continue;
            }
            if (properties.ignores(snapshot.vehicleId())) {
                // 좌표계만 겹치고 물리적으로는 다른 공간에 있는 차량. 여기서 빼지 않으면
                // 명령을 안 보내도 **남을 막는다**(TrafficControlProperties.ignores 주석).
                continue;
            }
            if (isStale(snapshot.receivedAt(), now)) {
                log.debug("교통 관제 판단 제외(위치 낡음): vehicleId={}, receivedAt={}",
                        snapshot.vehicleId(), snapshot.receivedAt());
                continue;
            }
            fresh.add(snapshot);
        }
        if (fresh.isEmpty()) {
            return List.of();
        }

        Map<String, VehicleStatus> statuses = loadStatuses(
                fresh.stream().map(VehicleLocationSnapshot::vehicleId).toList());

        List<VehicleMotion> motions = new ArrayList<>(fresh.size());
        for (VehicleLocationSnapshot snapshot : fresh) {
            motions.add(new VehicleMotion(
                    snapshot.vehicleId(), snapshot.x(), snapshot.y(),
                    snapshot.heading(), snapshot.speed(),
                    statuses.getOrDefault(snapshot.vehicleId(), VehicleStatus.UNKNOWN),
                    // 적재 여부를 함께 넘긴다. 예전에는 여기서 버려서 주기 상태기계가 status 로만
                    // 판정했고, 차량이 화물을 싣고도 IDLE 을 보고하면 적재를 영영 못 알아봤다.
                    snapshot.reportedLoaded(),
                    // 층 선택에 쓴다. 팔레트 포함·시뮬 단위라 쓰는 쪽이 환산한다.
                    snapshot.reportedCargoHeight()));
        }
        return motions;
    }

    /**
     * 차량 상태를 tick 당 한 번 읽는다.
     *
     * <p>상태는 위치와 달리 인메모리 스냅샷({@code VehicleLocationSnapshot})에 실려 오지 않는다.
     * 구역 점유 판정({@code isWorking})과 "누가 비켜야 하는가" 판정에 반드시 필요해서 여기서 읽는다.
     *
     * <p><b>tick 마다 DB 를 읽는 것이 맞나</b>: 대상은 지금 위치를 보내고 있는 차량 몇 대뿐이고
     * {@code vehicle_current_status} 는 PK 조회다. 이 정도 비용보다, 상태를 담기 위해 널리 쓰이는
     * 위치 레코드의 구조를 바꾸는 쪽이 위험이 크다고 판단했다. 차량 수가 크게 늘면 상태도 인메모리로
     * 올리는 것을 재검토할 것.
     */
    private Map<String, VehicleStatus> loadStatuses(List<String> vehicleIds) {
        Map<String, VehicleStatus> statuses = new LinkedHashMap<>();
        for (VehicleCurrentStatus row : statusMapper.findAllByVehicleIds(vehicleIds)) {
            statuses.put(row.getVehicleId(), row.getStatus());
        }
        return statuses;
    }

    private boolean isStale(OffsetDateTime receivedAt, OffsetDateTime now) {
        if (receivedAt == null) {
            return true;
        }
        return Duration.between(receivedAt, now).toMillis() > properties.staleLocationMs();
    }

    /** 현재 관제가 세워 둔 차량과 그 판단(진단·모니터링용). */
    public Map<String, TrafficStopDecision> heldVehicles() {
        return new LinkedHashMap<>(heldVehicles);
    }

    /** 제어 대상 차량 목록(진단용). */
    public Set<String> controlledVehicles() {
        return Set.copyOf(properties.vehicles());
    }
}
