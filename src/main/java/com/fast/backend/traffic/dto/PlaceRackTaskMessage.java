package com.fast.backend.traffic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 랙 적재 지시 ({@code fast/v1/vehicle/{id}/task}) — F팀 규격 §7.
 *
 * <pre>
 * { "taskId": "T-2", "action": "PLACE_RACK", "rack": "A001", "cargoId": "C-0007",
 *   "approach":    { "x": 5.00, "y": 9.30, "yaw": 3.1416 },
 *   "dock":        { "x": 2.60, "y": 9.30, "yaw": 3.1416 },
 *   "shelfHeight": 1.325,
 *   "reverseDist": 2.0 }
 * </pre>
 *
 * <p><b>시뮬과 실물이 서로 다른 필드를 본다.</b> 시뮬은 {@code rack} 이름으로 씬의 선반을 찾고,
 * 실물은 {@code approach}/{@code dock} 좌표로 주행한다. 한 메시지에 둘 다 담으면 양쪽 다
 * 동작하므로 <b>차량 종류에 따라 페이로드를 나누지 않는다</b> — 나누면 어느 쪽을 보낼지
 * 판단하는 분기가 생기고, 그 분기가 틀리면 그 차량만 조용히 안 움직인다.
 *
 * <p>좌표는 시뮬 좌표계이고 {@code shelfHeight} 도 시뮬 단위(1.325)다. 실물은 자기 쪽에서
 * ÷10 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceRackTaskMessage(
        String taskId,
        String action,
        String rack,
        String cargoId,
        Waypoint approach,
        Waypoint dock,
        Double shelfHeight,
        Double reverseDist
) {

    private static final String ACTION = "PLACE_RACK";

    public static PlaceRackTaskMessage of(
            String taskId,
            String rack,
            String cargoId,
            Waypoint approach,
            Waypoint dock,
            double shelfHeight,
            double reverseDist) {
        return new PlaceRackTaskMessage(
                taskId, ACTION, rack, cargoId, approach, dock, shelfHeight, reverseDist);
    }

    /** 한 지점. {@code yaw} 는 라디안. */
    public record Waypoint(double x, double y, double yaw) {
    }
}
