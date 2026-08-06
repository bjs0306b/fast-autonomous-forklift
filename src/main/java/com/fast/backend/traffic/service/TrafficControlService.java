package com.fast.backend.traffic.service;

import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.traffic.config.TrafficControlProperties;
import com.fast.backend.traffic.domain.CollisionPredictor;
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
 *       다만 <b>이미 세워 둔 차량은 풀지 않는다</b>(모르는 상태에서 재개시키지 않는다)</li>
 *   <li>정지는 즉시, 재개는 여유 거리({@code releaseDistanceM})에서 — 경계 채터링 방지</li>
 * </ul>
 */
@Service
public class TrafficControlService {

    private static final Logger log = LoggerFactory.getLogger(TrafficControlService.class);

    private final TrafficControlProperties properties;
    private final LatestVehicleLocationProvider locationProvider;
    private final VehicleCurrentStatusMapper statusMapper;
    private final WorkZoneProvider workZoneRegistry;
    private final VehicleCommandService commandService;
    private final TrafficControlEventMapper eventMapper;

    /** 관제가 세워 둔 차량 → 정지 판단. 여기 있으면 STOP 을 이미 보낸 것이다. */
    private final Map<String, TrafficStopDecision> heldVehicles = new ConcurrentHashMap<>();

    public TrafficControlService(
            TrafficControlProperties properties,
            LatestVehicleLocationProvider locationProvider,
            VehicleCurrentStatusMapper statusMapper,
            WorkZoneProvider workZoneRegistry,
            VehicleCommandService commandService,
            TrafficControlEventMapper eventMapper) {
        this.properties = properties;
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

        for (VehicleMotion motion : motions) {
            if (!properties.controls(motion.vehicleId())) {
                continue;   // 제어 대상이 아닌 차량은 관측만 하고 명령하지 않는다
            }
            Optional<TrafficStopDecision> decision = findStopReason(motion, motions, zones);
            if (decision.isPresent()) {
                hold(motion.vehicleId(), decision.get());
            } else if (heldVehicles.containsKey(motion.vehicleId())
                    && isSafeToRelease(motion, motions, zones)) {
                release(motion.vehicleId());
            }
        }
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

        for (VehicleMotion other : all) {
            if (other.vehicleId().equals(target.vehicleId())) {
                continue;
            }
            double gap = CollisionPredictor.effectiveGap(target, other, properties.predictionHorizonS());
            if (gap >= properties.holdDistanceM()) {
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
        for (VehicleMotion other : all) {
            if (other.vehicleId().equals(target.vehicleId())) {
                continue;
            }
            if (CollisionPredictor.currentGap(target, other) < release
                    || CollisionPredictor.predictedGap(target, other, properties.predictionHorizonS())
                        < release) {
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
     * 이미 세워 둔 차량은 재개 조건({@link #isSafeToRelease})을 통과하지 못해 <b>세워진 채로 남는다</b>.
     */
    private List<VehicleMotion> collectFreshMotions() {
        OffsetDateTime now = CommunicationTime.nowOffset();
        List<VehicleLocationSnapshot> fresh = new ArrayList<>();
        for (VehicleLocationSnapshot snapshot : locationProvider.findAllLatest()) {
            if (snapshot.x() == null || snapshot.y() == null) {
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
                    statuses.getOrDefault(snapshot.vehicleId(), VehicleStatus.UNKNOWN)));
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
