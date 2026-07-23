package com.fast.backend.isaac.dto;

import java.time.LocalDateTime;

/**
 * 백엔드가 {@code forklift/{id}/command} 토픽으로 발행하는 Isaac Sim 명령 메시지(prompt28.md 1장·7장
 * 합의 규격). 수신용이 아니라 발행용이다 — {@code IsaacForkliftCommandPublisher}가 이 레코드를
 * Jackson으로 직렬화해서 그대로 발행한다.
 *
 * <p>{@code commandId}는 이번 합의 JSON에 없다 — 명령 중복 방지·결과 추적에 필요할 수 있으나 합의
 * 근거 없이 외부 payload에 임의로 추가하지 않았다(answer28.md 18장 "미확정 사항" 참고).
 */
public record IsaacForkliftCommandMessage(
        String forkliftId,
        String command,
        Destination destination,
        LocalDateTime timestamp
) {

    public record Destination(Double x, Double y, Double direction) {
    }
}
