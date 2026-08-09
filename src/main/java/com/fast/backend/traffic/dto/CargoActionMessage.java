package com.fast.backend.traffic.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code fast/v1/vehicle/{id}/cargo} 화물 지시 (F팀 규격 §6).
 *
 * <pre>
 *   { "action": "align_bay" }                    바이 정면 정렬
 *   { "height": 0.15, "cargoId": "C-0007" }      바이에서 화물 받기
 *   { "action": "place_rack", "rack": "A1" }     랙에 적재
 *   { "action": "drop" }                          지금 자리에 내려놓기
 * </pre>
 *
 * <p><b>{@code height} 만 실물 미터다.</b> 카메라 측정값을 그대로 전달하기 때문이고,
 * 나머지 좌표는 전부 시뮬 좌표계다. 한 레코드에 단위가 다른 필드가 섞여 있으니 주의할 것.
 *
 * <p>{@code NON_NULL} 이라 쓰지 않는 필드는 JSON 에서 빠진다 — 규격의 네 가지 모양이
 * 그대로 만들어진다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CargoActionMessage(
        String action,
        Double height,
        String cargoId,
        String rack
) {

    /** 바이 정면 정렬. */
    public static CargoActionMessage alignBay() {
        return new CargoActionMessage("align_bay", null, null, null);
    }

    /**
     * 바이에서 화물 받기.
     *
     * @param heightM 화물 높이(<b>실물 m</b>)
     */
    public static CargoActionMessage load(double heightM, String cargoId) {
        return new CargoActionMessage(null, heightM, cargoId, null);
    }

    /** 랙에 적재. 시뮬은 랙 이름을 쓰고, 실물은 좌표를 쓴다(좌표는 task 로 따로 간다). */
    public static CargoActionMessage placeRack(String rackCode) {
        return new CargoActionMessage("place_rack", null, null, rackCode);
    }

    /** 지금 자리에 내려놓기. */
    public static CargoActionMessage drop() {
        return new CargoActionMessage("drop", null, null, null);
    }
}
