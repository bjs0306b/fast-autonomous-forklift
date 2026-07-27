package com.fast.backend.command.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 명령별 추가 데이터(prompt32.md 1장 8번의 선택 필드 {@code payload}).
 *
 * <p>명령마다 필요한 데이터가 다르지만, envelope 구조를 명령별로 쪼개면 수신 측이 명령 종류마다 다른
 * 파서를 써야 한다. 그래서 <b>공통 envelope + 명령별 payload</b> 형태를 유지하고, 현재 규격에서 실제로
 * 필요한 유일한 항목인 {@code destination}만 필드로 둔다(MOVE 명령 전용).
 *
 * <p>{@code destination}이 없는 명령(FORK_UP, EMERGENCY_STOP 등)은 확정 규격 예시대로 빈 객체
 * {@code {}}로 직렬화된다 — {@link JsonInclude.Include#NON_NULL}로 null 필드를 빼기 때문이다.
 * payload 자체를 null로 두지 않고 빈 객체를 보내는 이유는, 수신 측이 {@code payload} 키의 존재 여부를
 * 분기하지 않고 항상 같은 모양으로 읽을 수 있게 하기 위해서다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VehicleCommandPayload(
        VehicleCommandDestination destination
) {

    private static final VehicleCommandPayload EMPTY = new VehicleCommandPayload(null);

    /** destination이 필요 없는 명령용 빈 payload({@code {}}로 직렬화된다). */
    public static VehicleCommandPayload empty() {
        return EMPTY;
    }

    public static VehicleCommandPayload ofDestination(VehicleCommandDestination destination) {
        return new VehicleCommandPayload(destination);
    }
}
