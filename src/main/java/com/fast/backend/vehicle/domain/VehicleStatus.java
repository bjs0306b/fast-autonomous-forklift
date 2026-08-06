package com.fast.backend.vehicle.domain;

/**
 * 차량 상태 enum. Isaac 현재 계약의 HOLDING까지 보존한다.
 *
 * <p><b>확정 이전과의 차이</b>: 이전 버전은 {@code UNKNOWN/IDLE/ACTIVE/ERROR/OFFLINE} 5종만 두고
 * {@code MOVING}을 {@code ACTIVE}로 강제 변환했다. 팀 확정에 따라 <b>MOVING을 별도 상태로 보존</b>하고
 * {@code LIFTING/LOADING/UNLOADING/ESTOP}을 독립 상태로 추가했다 — 더 이상 어떤 상태도 다른 상태로
 * 흡수되지 않는다.
 *
 * <p>각 상태의 의미:
 * <ul>
 *   <li>{@link #UNKNOWN} — 상태 확인 불가</li>
 *   <li>{@link #IDLE} — 대기 또는 정지</li>
 *   <li>{@link #ACTIVE} — 일반 작업 활성 상태</li>
 *   <li>{@link #MOVING} — 주행 중</li>
 *   <li>{@link #LIFTING} — 포크 승강 중</li>
 *   <li>{@link #LOADING} — 적재 중</li>
 *   <li>{@link #UNLOADING} — 하역 중</li>
 *   <li>{@link #HOLDING} — 관제에 의한 일시 정지</li>
 *   <li>{@link #ESTOP} — 비상 정지 상태</li>
 *   <li>{@link #ERROR} — 오류 발생</li>
 *   <li>{@link #OFFLINE} — 통신 단절 또는 접속 종료</li>
 * </ul>
 *
 * <p>enum 상수 <b>선언 순서가 곧 상태 집계 응답의 항목 순서</b>다
 * ({@code VehicleService#countByStatus}가 {@code values()}를 그대로 순회한다).
 */
public enum VehicleStatus {
    UNKNOWN,
    IDLE,
    ACTIVE,
    MOVING,
    LIFTING,
    LOADING,
    UNLOADING,
    HOLDING,
    ESTOP,
    ERROR,
    OFFLINE;

    /**
     * 외부(REST 요청, MQTT 메시지)에서 들어온 원시 문자열을 안전하게 매핑한다.
     * null·빈 문자열·정의되지 않은 값은 모두 {@link #UNKNOWN}으로 처리해, 잘못된 상태값 하나 때문에
     * 차량 상태 갱신 전체가 예외로 실패하지 않도록 한다.
     *
     * <p><b>어떤 값도 다른 값으로 치환하지 않는다.</b> 특히 {@code "MOVING"}은 이제
     * {@link #MOVING} 그대로 보존된다(과거의 {@code MOVING → ACTIVE} 정규화는 prompt32.md 확정으로
     * 폐지됐다). 대소문자와 앞뒤 공백만 허용 오차로 흡수한다.
     */
    public static VehicleStatus fromRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        try {
            return VehicleStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
