package com.fast.backend.isaac.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code forklift/{id}/path} 토픽으로 수신되는 Isaac Sim 경로 메시지(prompt28.md 1장·6장 합의 규격).
 * 이 토픽은 실물 ROS2 쪽에 기존 사용 사례가 없어(저장소 전체 검색 결과 0건) 판별 없이 곧바로 이
 * DTO로 역직렬화한다.
 */
public record IsaacForkliftPathMessage(
        String forkliftId,
        List<Waypoint> waypoints,
        Goal goal,
        LocalDateTime timestamp
) {

    public record Waypoint(Double x, Double y) {
    }

    public record Goal(Double x, Double y, Double direction) {
    }
}
