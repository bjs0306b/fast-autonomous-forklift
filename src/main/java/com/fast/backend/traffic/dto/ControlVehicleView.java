package com.fast.backend.traffic.dto;

import com.fast.backend.traffic.domain.CyclePhase;

/**
 * 관제 화면용 차량 한 줄 (F팀 규격 {@code backend-control-impl} §0.6 응답 예).
 *
 * <p><b>기존 {@code GET /api/vehicles} 와 따로 두는 이유.</b> 그쪽은 차량 등록·위치를 보여주는
 * 조회 API 라 여러 화면이 이미 쓰고 있다. 거기에 관제 전용 필드({@code phase}, {@code joined},
 * {@code cycles})를 얹으면, 관제를 쓰지 않는 화면까지 매번 그 값을 받고 <b>관제가 꺼져 있을 때
 * 무엇이 맞는 값인지</b>를 각자 판단해야 한다.
 *
 * @param id       차량 ID
 * @param kind     {@code "real"} | {@code "sim"} — 화면이 실물을 구분해 표시할 수 있게
 * @param online   최근 3초 안에 telemetry 가 왔는가
 * @param controlled 관제 대상 목록({@code traffic.vehicles})에 있는가. <b>false 면 관제가
 *                   이 차량에 어떤 명령도 보내지 않는다</b> — 출발 버튼이 안 보이는 이유다
 * @param joined   순환로 합류 허가를 받았는가
 * @param held     사람이 개별로 세워 뒀는가(규칙 5)
 * @param phase    주기 단계. 주기가 꺼져 있으면 {@code null}
 * @param target   지금 향하는 스테이션·랙 이름
 * @param cycles   완료한 주기 수
 * @param busy     절차(정렬·도킹·적재) 수행 중인가. <b>{@code null} 은 "모른다"</b> —
 *                 시뮬만 이 필드를 보내고 실물은 C팀 구현 전까지 보내지 않는다
 * @param step     지금 수행 중인 절차 이름({@code align} {@code dock} …). 없으면 {@code null}
 */
public record ControlVehicleView(
        String id,
        String kind,
        boolean online,
        boolean controlled,
        boolean joined,
        boolean held,
        CyclePhase phase,
        String target,
        int cycles,
        Boolean busy,
        String step
) {
}
