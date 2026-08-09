package com.fast.backend.traffic.service;

import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.service.VehicleCommandService;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.traffic.config.TrafficControlProperties;
import com.fast.backend.traffic.domain.OperationState;
import com.fast.backend.vehicle.location.LatestVehicleLocationProvider;
import com.fast.backend.vehicle.location.VehicleLocationSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 전체 운행 상태와 합류 순서를 관리한다 (F팀 규격 {@code backend-control-impl} §0.6, 규칙 0).
 *
 * <p><b>"시작 시그널" 이라는 명령은 없다.</b> 차량은 스스로 출발하지 않고, 백엔드가 첫 목표를
 * 보내야 움직인다. 즉 <b>첫 목표를 주는 것이 곧 시작</b>이고, 이 서비스는 "누구에게 언제
 * 목표를 줘도 되는가"를 정한다.
 *
 * <p><b>시작 버튼이 세 대를 한꺼번에 내보내지 않는다.</b> {@link #releaseOne} 이 tick 마다
 * 한 대씩만 합류시키므로, 화면에는 "합류 중 (1/3)" 처럼 보인다. 동시에 내보내면 좁은 통로에서
 * 서로를 막아 교착이 난다.
 */
@Service
public class OperationService {

    private static final Logger log = LoggerFactory.getLogger(OperationService.class);

    /** telemetry 가 이 시간 이상 끊기면 LOST — 규격 §3 "끊김 판정". */
    private static final long LOST_THRESHOLD_MS = 3000L;

    private final TrafficControlProperties properties;
    private final LatestVehicleLocationProvider locationProvider;
    private final VehicleCommandService commandService;

    private volatile OperationState state = OperationState.IDLE;

    /** 순환로 합류 허가를 받은 차량. 여기 있어야 관제가 목표를 준다. */
    private final Set<String> joined = ConcurrentHashMap.newKeySet();

    /** 사용자가 개별로 세워 둔 차량. 관제 규칙이 세운 것과 구분한다(규칙 5). */
    private final Set<String> manuallyHeld = ConcurrentHashMap.newKeySet();

    /** 다음 합류 허가까지 남은 시간(ms). */
    private volatile long releaseCooldownMs = 0L;

    /**
     * 규칙 0 의 자동 합류를 돌릴지.
     *
     * <p><b>[운행 시작]</b> 은 켜고, <b>[차량 개별 출발]</b> 은 끈다. 개별로 한 대만 내보냈는데
     * 3초 뒤 나머지가 줄줄이 따라 나가면 "이 차만 보내려던" 의도와 어긋난다.
     */
    private volatile boolean autoRelease = false;

    public OperationService(
            TrafficControlProperties properties,
            LatestVehicleLocationProvider locationProvider,
            VehicleCommandService commandService) {
        this.properties = properties;
        this.locationProvider = locationProvider;
        this.commandService = commandService;
    }

    /** 관제 대상 전체(온라인 여부와 무관). 화면이 "연결 끊김"도 보여줘야 하므로 필요하다. */
    public List<String> controlledVehicles() {
        return List.copyOf(properties.vehicles());
    }

    /**
     * 시스템이 아는 차량 전체 — 관제 대상 + telemetry 를 보낸 적 있는 차량.
     *
     * <p><b>관제 대상만 내려주면 화면이 막힌다.</b> {@code traffic.vehicles} 가 비어 있으면
     * 목록이 통째로 비고, 사용자는 "출발 버튼이 왜 없지?" 를 알 길이 없다. 아는 차량은 다
     * 내려주되 {@code controlled} 플래그로 구분해, 화면이 이유를 보여줄 수 있게 한다.
     */
    public List<String> knownVehicles() {
        Set<String> all = new LinkedHashSet<>(properties.vehicles());
        for (VehicleLocationSnapshot snapshot : locationProvider.findAllLatest()) {
            if (snapshot.vehicleId() != null && !snapshot.vehicleId().isBlank()) {
                all.add(snapshot.vehicleId());
            }
        }
        return List.copyOf(all);
    }

    public OperationState state() {
        return state;
    }

    /** 지금 관제가 목표를 발행해도 되는 차량인가. */
    public boolean isDrivable(String vehicleId) {
        return state.isDriving() && joined.contains(vehicleId) && !manuallyHeld.contains(vehicleId);
    }

    public boolean isJoined(String vehicleId) {
        return joined.contains(vehicleId);
    }

    /** 사용자가 직접 세워 둔 차량인가(규칙 5 — 관제가 목표를 보내지 않는다). */
    public boolean isManuallyHeld(String vehicleId) {
        return manuallyHeld.contains(vehicleId);
    }

    public int joinedCount() {
        return joined.size();
    }

    /** 합류 대상 전체(제어 대상 중 온라인인 차량) 수. 화면의 "(1/3)" 분모다. */
    public int joinTargetCount() {
        return (int) onlineControlledVehicles().size();
    }

    // ── 운행 조작 ────────────────────────────────────────────────────────────────

    /**
     * 운행 시작. 합류 상태를 초기화하고 {@code RUNNING} 으로 바꾼다.
     *
     * <p>실제 출발은 여기서 일어나지 않는다 — 다음 tick 부터 {@link #releaseOne} 이
     * 한 대씩 내보낸다.
     *
     * @throws BusinessException 이미 시작했거나, 온라인 차량이 하나도 없을 때
     */
    public synchronized OperationState start() {
        if (!state.canStart()) {
            throw new BusinessException(ErrorCode.OPERATION_STATE_INVALID,
                    "이미 운행 중이거나 정지 상태입니다: " + state);
        }
        List<String> ready = onlineControlledVehicles();
        if (ready.isEmpty()) {
            // 아무도 없는데 RUNNING 으로 두면 화면은 "주행 중"인데 아무 일도 안 일어난다.
            throw new BusinessException(ErrorCode.OPERATION_NO_VEHICLE_ONLINE,
                    "연결된 차량이 없습니다. telemetry 수신을 먼저 확인하세요.");
        }
        joined.clear();
        manuallyHeld.clear();
        releaseCooldownMs = 0L;
        autoRelease = true;         // 전체 시작이므로 규칙 0 이 한 대씩 내보낸다
        state = OperationState.RUNNING;
        log.info("운행 시작: 대상 차량={}", ready);
        return state;
    }

    /**
     * 전체 일시정지. 모든 제어 대상에 STOP 을 보내고 {@code PAUSED} 로 바꾼다.
     *
     * <p><b>합류 상태({@link #joined})는 지우지 않는다.</b> 지우면 재개할 때 규칙 0 이 처음부터
     * 다시 돌아 세 대가 3 초 간격으로 다시 합류하는데, 이미 순환로 위에 있는 차들이라 그럴 이유가 없다.
     */
    public synchronized OperationState pause() {
        if (state == OperationState.IDLE) {
            throw new BusinessException(ErrorCode.OPERATION_STATE_INVALID,
                    "시작하지 않은 운행은 정지할 수 없습니다.");
        }
        broadcast("STOP");
        state = OperationState.PAUSED;
        log.warn("운행 일시정지: 합류 차량={}", joined);
        return state;
    }

    /**
     * 비상정지. 상태와 무관하게 언제나 받아들인다.
     *
     * <p>다른 조작과 달리 상태 검사를 하지 않는 이유: 비상정지가 "지금 상태가 아니라서" 거부되는
     * 일이 있으면 안 된다. IDLE 에서 눌렀다면 차량은 어차피 안 움직이지만, 명령은 그대로 나간다.
     */
    public synchronized OperationState emergencyStop() {
        broadcast("EMERGENCY_STOP");
        state = OperationState.ESTOPPED;
        log.error("운행 비상정지");
        return state;
    }

    /**
     * 재개. 개별로 세워 둔 차량까지 함께 푼다.
     *
     * @throws BusinessException 정지 상태가 아닐 때 — {@code IDLE} 에서 재개를 허용하면
     *         합류 순서를 건너뛴 채 전원이 한꺼번에 출발한다
     */
    public synchronized OperationState resume() {
        if (!state.canResume()) {
            throw new BusinessException(ErrorCode.OPERATION_STATE_INVALID,
                    "정지 상태가 아니라 재개할 수 없습니다: " + state);
        }
        manuallyHeld.clear();
        broadcast("RESUME");
        state = OperationState.RUNNING;
        log.info("운행 재개: 합류 차량={}", joined);
        return state;
    }

    /** 운행 종료. 전체 정지 + 합류 해제 + 초기화. */
    public synchronized OperationState reset() {
        broadcast("STOP");
        joined.clear();
        manuallyHeld.clear();
        releaseCooldownMs = 0L;
        autoRelease = false;
        state = OperationState.IDLE;
        log.info("운행 종료(초기화)");
        return state;
    }

    // ── 차량 개별 조작 ───────────────────────────────────────────────────────────

    /** 차량 하나를 세운다. 관제 규칙과 별개로 유지되며, 전체 재개나 개별 재개로만 풀린다. */
    public synchronized void holdVehicle(String vehicleId) {
        requireControlled(vehicleId);
        manuallyHeld.add(vehicleId);
        issue(vehicleId, "STOP");
        log.info("차량 개별 정지: vehicleId={}", vehicleId);
    }

    /** 차량 하나를 재개한다. */
    public synchronized void resumeVehicle(String vehicleId) {
        requireControlled(vehicleId);
        manuallyHeld.remove(vehicleId);
        issue(vehicleId, "RESUME");
        log.info("차량 개별 재개: vehicleId={}", vehicleId);
    }

    /**
     * 차량 하나만 출발시킨다 — 관제 화면에서 차량을 고르고 [출발] 을 눌렀을 때.
     *
     * <p><b>규격 §0.6 의 "첫 목표를 주는 것이 곧 시작"</b> 을 한 대에만 적용한 것이다.
     * 그래서 {@code IDLE} 이면 운행 상태도 함께 {@code RUNNING} 으로 올린다 — 안 그러면
     * 버튼을 눌러도 {@link #isDrivable} 이 막아 아무 일도 일어나지 않는다.
     *
     * <p><b>자동 합류는 켜지 않는다.</b> 이 차만 보내려는 조작인데 3초 뒤 나머지가 따라
     * 나가면 의도와 다르다. 전체를 돌리려면 [운행 시작] 을 쓴다.
     *
     * @throws BusinessException 관제 대상이 아니거나, telemetry 가 끊겼거나, 이미 출발한 경우
     */
    public synchronized void dispatchVehicle(String vehicleId) {
        requireControlled(vehicleId);
        if (!onlineControlledVehicles().contains(vehicleId)) {
            // 끊긴 차에 목표를 주면 되살아났을 때 밀린 명령이 한꺼번에 적용된다(규격 §0.5).
            throw new BusinessException(ErrorCode.OPERATION_NO_VEHICLE_ONLINE,
                    "telemetry 가 끊긴 차량은 출발시킬 수 없습니다: " + vehicleId);
        }
        if (joined.contains(vehicleId) && !manuallyHeld.contains(vehicleId)) {
            throw new BusinessException(ErrorCode.OPERATION_STATE_INVALID,
                    "이미 출발한 차량입니다: " + vehicleId);
        }
        if (state == OperationState.IDLE) {
            state = OperationState.RUNNING;
            autoRelease = false;    // 개별 출발 모드
        }
        manuallyHeld.remove(vehicleId);     // 세워 뒀던 차라면 함께 푼다
        joined.add(vehicleId);
        issue(vehicleId, "RESUME");         // 정지 상태면 task 가 무시되므로 먼저 푼다
        log.info("차량 개별 출발: vehicleId={}, 운행상태={}", vehicleId, state);
    }

    /** 지금 자동 합류(규칙 0)가 도는가. 화면이 "합류 중" 표시 여부를 정하는 데 쓴다. */
    public boolean isAutoRelease() {
        return autoRelease;
    }

    // ── 규칙 0 — 합류 순서 ───────────────────────────────────────────────────────

    /**
     * 한 tick 에 <b>한 대만</b> 합류를 허가한다. 실물(fk01 계열)이 언제나 먼저다.
     *
     * <p><b>왜 실물이 먼저인가.</b> 실물은 사람이 세트장에서 직접 다뤄야 해서 마음대로 세웠다
     * 다시 보낼 수 없다. 먼저 내보내 흐름을 잡게 하고 시뮬이 뒤를 따르는 편이, 나중에 앞차들
     * 사이로 끼어드는 것보다 안전하다.
     *
     * @param tickMs        이번 tick 간격(쿨다운 차감용)
     * @param gapToNearest  합류 후보 → 이미 합류한 차량 중 가장 가까운 앞차까지의 거리를 주는 함수.
     *                      앞이 비어 있으면 {@code null}
     * @return 새로 합류시킨 차량 ID. 없으면 {@code null}
     */
    public synchronized String releaseOne(
            long tickMs, double entryHeadwayM, long entryIntervalMs,
            java.util.function.Function<String, Double> gapToNearest) {

        if (!state.isDriving() || !autoRelease) {
            return null;    // 개별 출발 모드에서는 자동으로 더 내보내지 않는다
        }
        if (releaseCooldownMs > 0) {
            releaseCooldownMs = Math.max(0, releaseCooldownMs - tickMs);
            return null;
        }
        for (String vehicleId : entryOrder()) {
            if (joined.contains(vehicleId)) {
                continue;
            }
            Double gap = gapToNearest.apply(vehicleId);
            if (gap != null && gap < entryHeadwayM) {
                // 앞이 막혀 있다. 다른 차를 대신 내보내지 않는다 — 순서를 지켜야 실물이 밀리지 않는다.
                return null;
            }
            joined.add(vehicleId);
            releaseCooldownMs = entryIntervalMs;
            log.info("순환로 합류 허가: vehicleId={} ({}/{})",
                    vehicleId, joined.size(), joinTargetCount());
            return vehicleId;
        }
        return null;
    }

    /** 합류 순서 — 실물 먼저, 그다음 ID 순. */
    List<String> entryOrder() {
        return onlineControlledVehicles().stream()
                .sorted(Comparator.comparingInt((String id) -> isReal(id) ? 0 : 1)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /**
     * 실물 차량인가.
     *
     * <p>ID 규칙으로 판별한다 — {@code fk01} / {@code REAL-F01} 처럼 별칭이 여럿이라 한쪽만
     * 보면 놓친다. DB 에 "실물 여부" 컬럼이 생기면 그쪽으로 옮길 것.
     */
    public static boolean isReal(String vehicleId) {
        if (vehicleId == null) {
            return false;
        }
        String upper = vehicleId.toUpperCase();
        return upper.startsWith("FK") || upper.startsWith("REAL");
    }

    // ── 내부 ────────────────────────────────────────────────────────────────────

    /** 제어 대상 중 최근 {@value #LOST_THRESHOLD_MS}ms 안에 telemetry 가 온 차량. */
    public List<String> onlineControlledVehicles() {
        OffsetDateTime now = OffsetDateTime.now();
        Set<String> online = new LinkedHashSet<>();
        for (VehicleLocationSnapshot snapshot : locationProvider.findAllLatest()) {
            if (!properties.controls(snapshot.vehicleId())) {
                continue;
            }
            if (snapshot.receivedAt() == null
                    || Duration.between(snapshot.receivedAt(), now).toMillis() > LOST_THRESHOLD_MS) {
                continue;
            }
            online.add(snapshot.vehicleId());
        }
        return List.copyOf(online);
    }

    private void requireControlled(String vehicleId) {
        if (!properties.controls(vehicleId)) {
            throw new BusinessException(ErrorCode.OPERATION_VEHICLE_NOT_CONTROLLED,
                    "관제 대상 차량이 아닙니다: " + vehicleId);
        }
    }

    /**
     * 제어 대상 전체에 같은 명령을 보낸다.
     *
     * <p>한 대 실패가 나머지를 막지 않는다 — 전체 정지에서 그러면 일부만 서고 나머지는 계속 간다.
     */
    private void broadcast(String command) {
        for (String vehicleId : properties.vehicles()) {
            issue(vehicleId, command);
        }
    }

    private void issue(String vehicleId, String command) {
        try {
            commandService.issueCommand(vehicleId,
                    new VehicleCommandRequest(command, null, null, null, null));
        } catch (RuntimeException e) {
            log.error("운행 명령 발행 실패: vehicleId={}, command={}, error={}",
                    vehicleId, command, e.getMessage());
        }
    }

    /** 진단용 스냅샷. */
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("state", state);
        map.put("joined", List.copyOf(joined));
        map.put("manuallyHeld", List.copyOf(manuallyHeld));
        map.put("joinTargetCount", joinTargetCount());
        return map;
    }
}
