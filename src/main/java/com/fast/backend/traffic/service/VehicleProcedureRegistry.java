package com.fast.backend.traffic.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 차량이 지금 <b>절차(정렬·도킹·적재)를 수행 중인지</b> 기억한다 — telemetry 의
 * {@code busy}/{@code step} 필드 (F팀 {@code backend-mqtt-guide} §3).
 *
 * <p><b>왜 별도 저장소인가.</b> {@code VehicleLocationSnapshot} 은 위치 계약이라 여러 화면·
 * 서비스가 쓰고 생성자도 여럿이다. 거기에 절차 상태를 얹으면 관제와 무관한 코드까지 이 필드를
 * 알게 된다. 반대로 이 값은 <b>주기 상태기계 하나만</b> 쓰므로 옆에 따로 둔다.
 *
 * <p><b>{@code busy} 가 없는 차량이 있다.</b> 시뮬(sim02·sim03)만 이 필드를 보내고,
 * 실물(fk01)은 C팀 구현 전까지 안 보낸다. 그래서 "false" 와 "모른다"를 구별해야 한다 —
 * 모르는 차량을 {@code busy=false} 로 단정하면 정렬을 시작하지도 않았는데 끝난 것으로 읽힌다.
 */
@Component
public class VehicleProcedureRegistry {

    private final Map<String, Procedure> byVehicle = new ConcurrentHashMap<>();

    /**
     * telemetry 수신 시 갱신한다.
     *
     * <p>{@code busy} 가 {@code null} 이면 <b>기록하지 않는다</b> — 필드를 안 보내는 차량이
     * 한 번이라도 보낸 적 있는 값을 지워 버리면, 시뮬이 잠깐 필드를 빠뜨렸을 때 판정이 흔들린다.
     */
    public void update(String vehicleId, Boolean busy, String step) {
        if (vehicleId == null || vehicleId.isBlank() || busy == null) {
            return;
        }
        byVehicle.put(vehicleId, new Procedure(busy, step));
    }

    /**
     * 이 차량이 절차를 수행 중인가.
     *
     * @return {@code Optional.empty()} 는 <b>모른다</b>는 뜻이다({@code busy} 를 보내지 않는 차량).
     *         호출부는 이 경우 시간 기준으로만 판정해야 한다
     */
    public Optional<Boolean> isBusy(String vehicleId) {
        Procedure procedure = byVehicle.get(vehicleId);
        return procedure == null ? Optional.empty() : Optional.of(procedure.busy());
    }

    /** 지금 수행 중인 절차 이름. 없으면 빈 값. */
    public Optional<String> step(String vehicleId) {
        Procedure procedure = byVehicle.get(vehicleId);
        if (procedure == null || procedure.step() == null || procedure.step().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(procedure.step());
    }

    /** 운행 종료 시 초기화. */
    public void clear() {
        byVehicle.clear();
    }

    private record Procedure(boolean busy, String step) {
    }
}
