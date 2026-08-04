package com.fast.backend.transport.domain;

import com.fast.backend.station.domain.StationMeasurementStatus;

/**
 * 운반 작업이 실패한 이유를 관제 화면이 원인별로 구분할 수 있게 하는 코드.
 *
 * <p><b>왜 코드인가</b>: 실패 사유는 지금까지 로그 문자열로만 남아 화면에서 사라졌다. 사람이 읽을
 * 문구를 백엔드가 만들어 내려보내면 같은 의미가 백엔드 문자열과 프론트 문구 두 벌로 갈라진다.
 * 그래서 백엔드는 <b>코드와 원본 측정 상태만</b> 내려주고, 사용자 문구는 프론트가 한 곳에서
 * 매핑한다({@code lib/monitoring/measurementFailure.ts}).
 *
 * <p>DB {@code transport_task.failure_code} 에 이름 그대로 저장된다 — 새 값을 추가할 때
 * {@code chk_transport_task_failure_code} CHECK 제약도 함께 넓혀야 한다.
 */
public enum TaskFailureCode {

    /** 화물 자체가 감지되지 않았다. 파렛트 유무와 무관하다. */
    MEASUREMENT_NO_DETECTION,

    /** 거리 센서 측정이 신뢰할 수 없는 값이었다. */
    MEASUREMENT_DISTANCE_UNRELIABLE,

    /** 치수는 쟀지만 파렛트를 못 찾아 편하중·전복 판정이 불가능했다. */
    MEASUREMENT_PALLET_NOT_DETECTED,

    /** 측정은 정상인데 적재 상태가 부적합하다 — 재측정이 아니라 사람이 다시 쌓아야 한다. */
    PLACEMENT_INELIGIBLE,

    /** TTL 이 지나도록 측정 결과가 도착하지 않았다. 백엔드가 아는 것은 "응답이 없었다"뿐이다. */
    MEASUREMENT_NO_RESPONSE,

    /** 측정은 통과했으나 조건에 맞는 적재 위치를 잡지 못했다(빈 슬롯 없음·예약 경합 등). */
    PLACEMENT_SLOT_UNAVAILABLE;

    /**
     * 측정 상태와 적재 적합 여부로 실패 코드를 정한다.
     *
     * <p>우선순위는 측정 상태가 먼저다 — {@code status} 가 OK 가 아니면 적재 적합 여부는 애초에
     * 판정된 적이 없다(전복·돌출 값이 null 이라 {@code placementEligible} 은 항상 false 로 나온다).
     * 그 false 를 "적재 부적합"으로 보여주면 원인을 정반대로 안내하게 된다.
     *
     * @param status 저장된 측정 상태. null 이면 측정 결과 자체가 없다는 뜻이라 무응답으로 본다.
     */
    public static TaskFailureCode fromMeasurement(StationMeasurementStatus status) {
        if (status == null) {
            return MEASUREMENT_NO_RESPONSE;
        }
        return switch (status) {
            case NO_DETECTION -> MEASUREMENT_NO_DETECTION;
            case UNRELIABLE -> MEASUREMENT_DISTANCE_UNRELIABLE;
            case DIMENSIONS_ONLY -> MEASUREMENT_PALLET_NOT_DETECTED;
            // status=ok 인데 실패했다면 남은 원인은 적재 부적합뿐이다(높이·전복·돌출 게이트).
            case OK -> PLACEMENT_INELIGIBLE;
        };
    }
}
