package com.fast.backend.storage.domain;

/**
 * 선반 슬롯 상태(prompt46.md 5장).
 *
 * <ul>
 *   <li>{@link #EMPTY} — 비어 있어 추천 대상이 되는 상태</li>
 *   <li>{@link #RESERVED} — 추천되어 예약된 상태(작업 완료 시 OCCUPIED, 실패·취소 시 EMPTY로 복귀)</li>
 *   <li>{@link #OCCUPIED} — 화물이 실제로 적재된 상태</li>
 *   <li>{@link #BLOCKED} — 사용 불가. 추천 대상에서 제외</li>
 * </ul>
 *
 * <p>추천 대상은 {@link #EMPTY}뿐이다. {@link #BLOCKED}/{@link #OCCUPIED}/{@link #RESERVED}는 제외한다.
 */
public enum StorageSlotStatus {
    EMPTY,
    RESERVED,
    OCCUPIED,
    BLOCKED;

    /**
     * 허용 전이(prompt47.md 12장): {@code EMPTY→RESERVED}, {@code RESERVED→OCCUPIED}, {@code RESERVED→EMPTY}.
     * 그 외(OCCUPIED→RESERVED, BLOCKED→*, EMPTY→OCCUPIED, OCCUPIED→EMPTY 등)는 모두 차단한다.
     *
     * <p>실제 예약은 조건부 UPDATE({@code WHERE status='EMPTY'})가 원자적으로 강제하고, 이 메서드는
     * 완료·실패·취소 시 Service가 슬롯 상태를 바꾸기 전에 방어적으로 검증하는 용도다.
     */
    public boolean canTransitionTo(StorageSlotStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case EMPTY -> target == RESERVED;
            case RESERVED -> target == OCCUPIED || target == EMPTY;
            case OCCUPIED, BLOCKED -> false;
        };
    }
}
