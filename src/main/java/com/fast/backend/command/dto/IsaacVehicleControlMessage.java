package com.fast.backend.command.dto;

/**
 * Isaac Sim의 {@code fast/v1/vehicle/{id}/control} 및 {@code fast/v1/control/all} 계약.
 *
 * <p>규격({@code isaac_sim/docs/interface-spec.md} §3.2):
 * <pre>
 * { "ts": 1784568107610, "command": "ESTOP" }
 * command: ESTOP | RESUME | HOLD | CANCEL_TASK
 * </pre>
 *
 * <p>{@code HOLD} 는 FR-502 교차 제어용("잠깐 대기하라")이고, 그 해제가 {@code RESUME} 이다.
 * 백엔드의 {@code STOP → HOLD}, {@code RESUME/RESET_ESTOP → RESUME} 변환은
 * {@code VehicleCommandPublisher.publishIsaacControl} 에 있다.
 *
 * <p><b>{@code ts} 를 빠뜨리면 안 된다.</b> 규격이 명시한 필드이고, 수신 측이 필수로 읽으면
 * 명령이 조용히 무시된다 — 발행은 성공하고 차량만 반응하지 않아 원인을 찾기 어렵다
 * (2026-08-06 에 누락을 발견해 추가).
 *
 * @param ts      발행 시각(epoch milliseconds). telemetry 의 {@code ts} 와 같은 단위다
 * @param command ESTOP / RESUME / HOLD / CANCEL_TASK
 */
public record IsaacVehicleControlMessage(long ts, String command) {

    /** 지금 시각으로 만든다. 호출부가 매번 {@code System.currentTimeMillis()} 를 쓰지 않도록. */
    public static IsaacVehicleControlMessage now(String command) {
        return new IsaacVehicleControlMessage(System.currentTimeMillis(), command);
    }
}
