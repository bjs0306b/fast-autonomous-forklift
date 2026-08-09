package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Isaac Sim 이 {@code fast/v1/vehicle/{id}/telemetry} 로 약 5 Hz 발행하는 차량 telemetry.
 *
 * <pre>
 * {
 *   "vehicleId": "sim01",
 *   "ts": 1785946947471,
 *   "pose": { "x": 1.55, "y": 0.4, "yaw": 0.0 },
 *   "velocity": { "linear": 0.0, "angular": 0.0 },
 *   "forkHeight": 0.0, "loaded": false, "cargoId": null,
 *   "state": "IDLE", "taskId": null, "battery": 100.0
 * }
 * </pre>
 *
 * <p><b>기존 {@code ForkliftLocationMessage} 와 다른 계약이라 별도 DTO 를 둔다.</b> 차이가 세 군데 있다.
 * <ul>
 *   <li>좌표가 {@code position} 이 아니라 {@code pose} 이고, 그 안에 방향이 <b>radian</b>({@code yaw})으로 들어온다</li>
 *   <li>시각이 ISO-8601 이 아니라 <b>epoch milliseconds</b>({@code ts})다</li>
 *   <li>차량 ID 가 {@code sim01} 처럼 DB 등록 ID 와 다르다</li>
 * </ul>
 * 억지로 한 DTO 로 합치면 어느 필드가 어느 계약의 것인지 알 수 없게 되므로, 변환은
 * {@code IsaacVehicleTelemetryService} 한 곳에서만 한다.
 *
 * <p>필수는 {@code vehicleId}·{@code pose.x}·{@code pose.y} 뿐이다. 나머지는 전부 nullable 이며,
 * <b>부가 필드 하나가 비어 있다고 위치 표시를 막지 않는다</b> — 배터리를 못 읽었다고 차량이 미니맵에서
 * 사라지는 것이 더 나쁜 결과다.
 *
 * @param cargoId 화물 식별자. 백엔드 DB 는 {@code BIGINT} 지만 Isaac 은 문자열/`null` 로 보낼 수 있어
 *                <b>문자열 그대로</b> 받는다. 이번 범위에서는 사용하지 않는다(8장 보류 항목)
 */
public record IsaacVehicleTelemetryMessage(
        String vehicleId,
        Long ts,
        Pose pose,
        Velocity velocity,
        Double forkHeight,
        @JsonAlias("hasCargo") Boolean loaded,
        String cargoId,
        String state,
        String taskId,
        Double battery,
        Cargo cargo,
        /**
         * 차량이 절차(정렬·도킹·적재)를 수행 중인가 — <b>단계 전환은 이 값으로 판정한다</b>.
         *
         * <p>{@code state} 로는 안 된다. 정렬이 끝나도 {@code state} 는 화물을 받을 때까지
         * {@code LOADING} 을 유지하므로 "정렬이 끝났는지"를 구별할 수 없다.
         *
         * <p><b>시뮬(sim02·sim03)만 보낸다.</b> 실물(fk01)은 C팀 구현 전까지 이 필드가 없어
         * {@code null} 이고, 그때는 최소 대기 시간만으로 판정해야 한다.
         */
        Boolean busy,
        /** 지금 수행 중인 절차 이름({@code align} {@code fork} {@code dock} {@code place} …). */
        String step
) {

    /** 기존 호출부 호환용 생성자. 신규 cargo 객체가 없으면 null로 둔다. */
    public IsaacVehicleTelemetryMessage(
            String vehicleId,
            Long ts,
            Pose pose,
            Velocity velocity,
            Double forkHeight,
            Boolean loaded,
            String cargoId,
            String state,
            String taskId,
            Double battery) {
        this(vehicleId, ts, pose, velocity, forkHeight, loaded, cargoId, state, taskId, battery,
                null, null, null);
    }

    /** {@code busy}/{@code step} 이전 호출부 호환. */
    public IsaacVehicleTelemetryMessage(
            String vehicleId,
            Long ts,
            Pose pose,
            Velocity velocity,
            Double forkHeight,
            Boolean loaded,
            String cargoId,
            String state,
            String taskId,
            Double battery,
            Cargo cargo) {
        this(vehicleId, ts, pose, velocity, forkHeight, loaded, cargoId, state, taskId, battery,
                cargo, null, null);
    }

    /** @param yaw <b>radian</b>. 기존 위치 계약은 degree 이므로 서비스에서 변환한다 */
    public record Pose(Double x, Double y, Double yaw) {
    }

    /** @param linear m/s. 기존 위치 계약의 {@code speed} 에 대응한다 */
    public record Velocity(Double linear, Double angular) {
    }

    /** 시뮬레이터가 보고하는 화물 전체 크기. h에는 팔레트가 포함되므로 AI cargoHeight와 구분한다. */
    public record Cargo(String id, Double w, Double d, @JsonAlias("height") Double h) {
    }
}
