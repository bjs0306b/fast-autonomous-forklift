package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code forklift/{id}/path} 토픽으로 수신되는 Isaac Sim 경로 메시지(prompt28.md 1장·6장 합의 규격).
 * 이 토픽은 실물 ROS2 쪽에 사용 사례가 없어 판별 없이 곧바로 이 DTO로 역직렬화한다.
 *
 * <p>좌표 단위는 <b>m</b>, {@code goal.heading}은 <b>degree</b>다(prompt32.md 1장 5번 확정).
 * {@code goal.direction}은 과도기 호환용 읽기 alias이며, 백엔드가 WebSocket으로 내보낼 때는 항상
 * {@code heading}이다({@link IsaacForkliftLocationMessage} Javadoc의 alias 주의사항 동일 적용).
 *
 * <p>{@code timestamp}는 {@code +09:00} {@link OffsetDateTime}이다(prompt32.md 1장 6번).
 */
public record IsaacForkliftPathMessage(
        String forkliftId,
        List<Waypoint> waypoints,
        Goal goal,
        OffsetDateTime timestamp
) {

    public record Waypoint(Double x, Double y) {
    }

    public record Goal(Double x, Double y, @JsonAlias("direction") Double heading) {
    }
}
