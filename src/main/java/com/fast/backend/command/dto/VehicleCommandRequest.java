package com.fast.backend.command.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/vehicles/{vehicleId}/commands} 요청 바디.
 *
 * <p><b>REST 요청 DTO와 MQTT 발행 DTO를 의도적으로 분리했다</b>(prompt32.md 3장 2번). 이 레코드에는
 * {@code commandId}와 {@code timestamp}가 <b>없다</b> — 사용자가 직접 넣지 못하게 하기 위해서다.
 * 백엔드({@code VehicleCommandService})가 다음을 전담한다:
 * <ul>
 *   <li>{@code commandId} 생성(UUID)</li>
 *   <li>{@code timestamp} 생성(+09:00 현재 시각)</li>
 *   <li>{@code targetSystem}/{@code commandCategory} 조합 결정·검증</li>
 *   <li>MQTT 발행 DTO({@link VehicleCommandMessage}) 생성</li>
 * </ul>
 *
 * <p>{@code targetSystem}/{@code commandCategory}는 <b>선택</b>이다. 명령 이름만으로 확정 조합이
 * 정해지므로({@link com.fast.backend.command.domain.VehicleCommandType}) 보내지 않으면 백엔드가 채운다.
 * 보낸 경우에는 확정 조합과 일치하는지 검증하고, 어긋나면 400으로 거부한다 — 호출자가 잘못 알고 있는
 * 조합을 조용히 고쳐서 발행하면 안 되기 때문이다.
 *
 * <p>{@code destination}은 MOVE 명령에서만 필수다.
 */
public record VehicleCommandRequest(
        @NotBlank String command,
        String targetSystem,
        String commandCategory,
        VehicleCommandDestination destination
) {
}
