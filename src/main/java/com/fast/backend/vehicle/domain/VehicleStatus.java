package com.fast.backend.vehicle.domain;

/**
 * 차량 상태의 임시 최소 enum(prompt16.md 5장 "방법 A").
 *
 * <p><b>이 값 자체는 아직 최종 확정이 아니다.</b> ROS2·임베디드·Isaac Sim 담당자와 실제 상태 값 규격
 * 합의가 끝나면 아래 enum 상수 목록이 바뀔 수 있다(예: MOVING/LOADING/UNLOADING/CHARGING/EMERGENCY_STOP
 * 후보 추가, `answer15.md` 15장 참고). 이 enum을 참조하는 코드가 적을수록 나중에 바꾸기 쉬우므로,
 * DB/DTO/서비스 어디에서도 이 5개 값 외의 것이 "있다고 가정"하지 않는다.
 *
 * <p>{@link #fromRaw(String)}는 방법 B(문자열 저장 + 안전한 매핑)의 장점을 함께 취한 것이다 — 알 수 없는
 * 문자열이 들어와도 애플리케이션이 죽지 않고 {@link #UNKNOWN}으로 처리된다(prompt16.md 11장 조건).
 */
public enum VehicleStatus {
    UNKNOWN,
    IDLE,
    ACTIVE,
    ERROR,
    OFFLINE;

    /**
     * 외부(REST 요청, MQTT 메시지)에서 들어온 원시 문자열을 안전하게 매핑한다.
     * null·빈 문자열·정의되지 않은 값은 모두 {@link #UNKNOWN}으로 처리해, 잘못된 상태값 하나 때문에
     * 차량 상태 갱신 전체가 예외로 실패하지 않도록 한다.
     *
     * <p>{@code MOVING}은 ROS2 내부 표기다 — 팀 합의(prompt25.md 1.1장)로 주행 중 상태는 최종적으로
     * {@link #ACTIVE} 하나로 통일하기로 확정됐다. ROS2가 실제로는 {@code ACTIVE}를 MQTT payload에 실어
     * 보내기로 했지만, 호환을 위해 {@code MOVING}이 들어와도 여기서 {@link #ACTIVE}로 정규화한다. 다른
     * 값(IDLE/ERROR/OFFLINE 등)의 매핑은 건드리지 않는다.
     */
    public static VehicleStatus fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String normalized = raw.trim().toUpperCase();
        if ("MOVING".equals(normalized)) {
            normalized = ACTIVE.name();
        }
        try {
            return VehicleStatus.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
