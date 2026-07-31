package com.fast.backend.station.domain;

import java.time.LocalDateTime;

/**
 * {@code station_state} 한 행 — 단일 설비의 현재 점유 상태(prompt106).
 *
 * <p>{@code StationSession}(세션 자체)과 다르다. 세션은 "어떤 화물을 재는가"이고, 이 클래스는
 * "설비를 지금 누가 잡고 있고 언제부터인가"다. TTL 만료 판정과 로그가 {@code acquiredAt} 을
 * 필요로 해서 별도 조회 모델로 두었다.
 *
 * <p>유휴 상태면 두 필드가 <b>함께</b> {@code null} 이다 — 점유·해제 SQL 이 항상 둘을 같이 세팅한다.
 * {@code activeSessionId} 는 있는데 {@code acquiredAt} 이 {@code null} 인 조합은 이 컬럼이 없던
 * 시절의 행에서만 나오며, 그 경우는 만료로 간주한다({@code isExpired} 참고).
 */
public class StationState {

    /**
     * 단일 스테이션 행 고정값(항상 1).
     *
     * <p>비즈니스적으로 쓰이지는 않지만 <b>조회에 반드시 포함해야 한다</b>. MyBatis 는 선택한 컬럼이
     * 전부 NULL 이면 객체를 만들지 않고 {@code null} 을 돌려주는데, 유휴 상태가 정확히 그 경우
     * (activeSessionId·acquiredAt 둘 다 NULL)라 "행이 없음"과 "유휴"가 구분되지 않는다.
     * NOT NULL 인 이 컬럼을 함께 읽어 항상 객체가 만들어지게 한다.
     */
    private Integer singletonId;

    private String activeSessionId;
    private LocalDateTime acquiredAt;

    public Integer getSingletonId() {
        return singletonId;
    }

    public void setSingletonId(Integer singletonId) {
        this.singletonId = singletonId;
    }

    public String getActiveSessionId() {
        return activeSessionId;
    }

    public void setActiveSessionId(String activeSessionId) {
        this.activeSessionId = activeSessionId;
    }

    public LocalDateTime getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(LocalDateTime acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    /** 점유 중인지. 세션 식별자가 있으면 점유로 본다. */
    public boolean isOccupied() {
        return activeSessionId != null;
    }

    /**
     * 주어진 기준 시각까지 만료됐는지.
     *
     * <p>{@code acquiredAt} 이 {@code null} 이면 <b>만료로 본다</b> — 언제부터 점유됐는지 알 수 없는
     * 행을 영구 점유로 취급하면 이 기능이 고치려는 잠금이 그대로 남기 때문이다. SQL 의 점유 조건과
     * 같은 규칙이라, 판정 결과가 어긋나지 않는다.
     *
     * @param expiredBefore {@code now - ttl}. 이 시각 이전에 점유됐으면 만료다.
     */
    public boolean isExpired(LocalDateTime expiredBefore) {
        return acquiredAt == null || !acquiredAt.isAfter(expiredBefore);
    }
}
