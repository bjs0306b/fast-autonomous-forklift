package com.fast.backend.command.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * 이동 명령의 목표 지점(prompt32.md 1장 9번 확정 규격).
 *
 * <pre>
 * { "x": 5.0, "y": 6.0, "heading": 180.0, "frameId": "map" }
 * </pre>
 *
 * <p>단위·규격(1장 5번·9번 확정):
 * <ul>
 *   <li>{@code x}/{@code y} — <b>m</b>, 필수, finite</li>
 *   <li>{@code heading} — <b>degree</b>, [0,360)로 정규화해서 발행한다</li>
 *   <li>{@code frameId} — {@code map} 또는 {@code odom}만 허용. 생략 시 기본값 {@code map}</li>
 * </ul>
 *
 * <p>{@code direction}은 과도기 호환용 역직렬화 alias다 — Isaac 브리지가 아직 옛 필드 이름을 쓸 수 있어
 * 읽기만 허용하고, 이 백엔드가 <b>발행하는 JSON은 항상 {@code heading}</b>이다(1장 5번). alias는 팀
 * 전원이 heading으로 전환한 것이 확인되면 제거한다.
 */
public record VehicleCommandDestination(
        Double x,
        Double y,
        @JsonAlias("direction") Double heading,
        String frameId
) {
}
