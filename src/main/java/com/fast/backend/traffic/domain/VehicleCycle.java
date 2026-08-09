package com.fast.backend.traffic.domain;

/**
 * 차량 한 대의 주기 진행 상태 (F팀 규격 {@code backend-control-impl} §3).
 *
 * <p><b>메모리에만 둔다.</b> 관제가 재시작되면 주기는 처음부터 다시 시작하는 것이 맞다 —
 * 재시작 시점에 차량이 어느 단계였는지를 DB 에서 복원해 봐야, 그 사이 차량이 실제로 무엇을
 * 했는지는 알 수 없다. 잘못 복원한 단계로 이어 가면 빈 포크로 랙에 가는 식의 사고가 난다.
 *
 * <p>tick 스레드 하나만 이 객체를 고치므로 동기화하지 않는다. 다만 조회는 다른 스레드
 * (REST·WebSocket)에서 일어날 수 있어 필드를 {@code volatile} 로 둔다.
 */
public final class VehicleCycle {

    private final String vehicleId;

    private volatile CyclePhase phase = CyclePhase.TO_BAY;
    /** 지금 향하는 스테이션 이름(화면 표시용). */
    private volatile String target = "BAY";
    /** 배정된 랙 코드. {@code RACK} 단계에서만 의미가 있다. */
    private volatile String rackCode;
    /** 다음에 쓸 랙 번호(차량별 배정표의 인덱스). */
    private volatile int rackIndex;
    /** 완료한 주기 수. */
    private volatile int cycles;

    /**
     * 마지막으로 보낸 목표. <b>같은 목표를 반복 발행하지 않기 위해</b> 쓴다.
     *
     * <p>규격 §10 함정 5번 — 매 tick 같은 목표를 보내면 차량이 목표를 계속 갈아타며 버벅인다.
     */
    private volatile String lastGoal;

    /** 작업 단계의 시작 시각(ms). 무한 대기를 막는 안전장치. */
    private volatile long workStartedAtMs;

    /**
     * 순환로를 벗어나 스테이션으로 직행하는 중인가 (규칙 1 — 일방통행).
     *
     * <p><b>왜 한 번 정하면 유지하는가.</b> 진입 판정은 "진입점까지 남은 호장"으로 하는데,
     * 통로를 벗어나기 시작하면 투영 지점이 튀어 그 값이 다시 커진다. 매 tick 다시 재면
     * 빠져나가다 말고 순환로로 돌아가기를 반복한다. 그래서 한 번 진입하면 단계가 끝날
     * 때까지 붙잡는다.
     */
    private volatile boolean approaching;

    /** 정체 감시 — 마지막으로 "움직였다"고 인정한 위치와 시각. */
    private volatile double lastX;
    private volatile double lastY;
    private volatile long lastMovedAtMs;

    public VehicleCycle(String vehicleId, long nowMs) {
        this.vehicleId = vehicleId;
        this.lastMovedAtMs = nowMs;
        this.workStartedAtMs = nowMs;
        this.lastX = Double.NaN;
        this.lastY = Double.NaN;
    }

    public String vehicleId() {
        return vehicleId;
    }

    public CyclePhase phase() {
        return phase;
    }

    public String target() {
        return target;
    }

    public String rackCode() {
        return rackCode;
    }

    public int cycles() {
        return cycles;
    }

    public String lastGoal() {
        return lastGoal;
    }

    /** 다음 단계로 넘어간다. 작업 타이머를 새로 잡고 목표 기억을 지운다. */
    public void advance(long nowMs) {
        CyclePhase before = phase;
        phase = phase.next();
        if (before == CyclePhase.RACK) {
            cycles++;
            rackCode = null;
        }
        workStartedAtMs = nowMs;
        // 단계가 바뀌면 목표도 바뀐다. 지우지 않으면 새 단계의 첫 목표가 "이미 보냈다"로 걸러진다.
        lastGoal = null;
        approaching = false;    // 새 단계는 다시 순환로부터 시작한다
    }

    /** 지금 스테이션으로 직행 중인가. */
    public boolean isApproaching() {
        return approaching;
    }

    /** 순환로를 벗어나 스테이션으로 직행하기 시작한다. */
    public void markApproaching() {
        approaching = true;
    }

    /**
     * 진입을 취소하고 순환로 주행으로 되돌린다.
     *
     * <p>정체가 감지됐을 때 쓴다 — 진입하다 막혔으면 경로를 다시 잡아야 한다.
     * 참조 구현({@code demo_loop2.py})은 재전송 3 회마다 이걸 했는데, 여기서는 정체가
     * 잡힐 때마다 한다. 정체 판정 자체가 20 초·0.3m 로 이미 보수적이라 더 세분할 이득이 없다.
     */
    public void cancelApproach() {
        approaching = false;
    }

    /**
     * 실패 복구 — 주기를 첫 단계({@code TO_BAY})로 되돌린다.
     *
     * <p><b>{@link #cycles} 를 올리지 않는다.</b> 예전에는 호출부가 {@code TO_BAY} 가 될 때까지
     * {@link #advance} 를 반복했는데, 그 경로가 {@code RACK} 을 지나면서 완료 주기 수를 올렸다.
     * 적재에 실패할 때마다 "완료 주기"가 하나씩 늘어, 랙에 한 번도 못 간 차량이 8 주기를
     * 처리한 것으로 집계됐다(2026-08-10 실측). 실패는 실패로 세야 지표를 믿을 수 있다.
     */
    /**
     * 복귀를 시작한다 — 랙이 다 차서 놓을 자리가 없을 때.
     *
     * <p>주기 수는 건드리지 않는다. 여기까지 완료한 것은 그대로 완료다.
     */
    public void startReturning(long nowMs) {
        phase = CyclePhase.RETURNING;
        target = "HOME";
        rackCode = null;
        workStartedAtMs = nowMs;
        lastGoal = null;
        approaching = false;
    }

    public void restartToBay(long nowMs) {
        phase = CyclePhase.TO_BAY;
        target = "BAY";
        rackCode = null;
        workStartedAtMs = nowMs;
        lastGoal = null;
        approaching = false;
    }

    /** 랙을 배정한다. 같은 주기에 두 번 부르지 않도록 {@link #rackCode()} 로 확인하고 쓴다. */
    public void assignRack(String code) {
        this.rackCode = code;
        this.rackIndex++;
    }

    public int rackIndex() {
        return rackIndex;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    /**
     * 이 목표를 보내야 하는가. 보내야 하면 기억해 두고 {@code true}.
     *
     * <p>중복 발행을 여기 한 곳에서 막는다 — 호출부마다 비교하면 한 군데만 빠뜨려도
     * 그 경로에서 차량이 버벅인다.
     */
    public boolean shouldSendGoal(String goalKey) {
        if (goalKey == null || goalKey.equals(lastGoal)) {
            return false;
        }
        lastGoal = goalKey;
        return true;
    }

    /** 정체 감시 후 목표를 다시 보내게 한다. */
    public void forgetGoal() {
        lastGoal = null;
    }

    /** 작업 단계가 시작된 지 {@code limitMs} 를 넘겼는가. */
    public boolean workTimedOut(long nowMs, long limitMs) {
        return nowMs - workStartedAtMs > limitMs;
    }

    /**
     * 작업 단계에 들어온 지 {@code minMs} 이상 지났는가 — <b>너무 빨리 다음으로 넘어가는 것</b>을 막는다.
     *
     * <p>{@link #workTimedOut} 의 반대편이다. 그쪽은 "너무 오래 걸리면 포기"이고, 이쪽은
     * "아직 시작도 안 했는데 끝났다고 보지 않기"다. 둘 다 필요한 이유:
     * 차량 상태로만 완료를 판정하면, 지시를 보낸 직후 차량이 아직 이전 상태(IDLE 등)일 때
     * <b>"조건 불일치 = 완료"로 읽혀 즉시 넘어간다.</b>
     */
    public boolean workSettled(long nowMs, long minMs) {
        return nowMs - workStartedAtMs >= minMs;
    }

    /**
     * 위치를 갱신하고 <b>정체 여부</b>를 돌려준다.
     *
     * <p>정체로 판정되면 마지막 이동 시각을 지금으로 밀어 둔다 — 안 그러면 다음 tick 마다
     * 계속 정체로 잡혀 목표가 초당 두 번씩 재전송된다.
     *
     * @param moveThresholdM 이보다 많이 움직였으면 "움직였다"로 본다
     * @param stallMs        이 시간 동안 움직이지 않으면 정체
     */
    public boolean updateAndCheckStall(
            double x, double y, long nowMs, double moveThresholdM, long stallMs) {

        if (Double.isNaN(lastX) || Math.hypot(x - lastX, y - lastY) > moveThresholdM) {
            lastX = x;
            lastY = y;
            lastMovedAtMs = nowMs;
            return false;
        }
        if (nowMs - lastMovedAtMs > stallMs) {
            lastMovedAtMs = nowMs;
            return true;
        }
        return false;
    }

    /**
     * 정체 감시 시각만 미룬다.
     *
     * <p>관제나 사용자가 세워 둔 동안 쓴다. 안 그러면 <b>해제 직후 정체로 오판</b>해
     * 목표를 재전송한다(규격 §10 함정 7번).
     */
    public void touchStallTimer(long nowMs) {
        lastMovedAtMs = nowMs;
    }
}
