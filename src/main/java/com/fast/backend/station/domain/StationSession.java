package com.fast.backend.station.domain;

/**
 * {@code station_session} 한 행 — 화물 하나에 대한 측정 세션(FR-202 신규, prompt85).
 *
 * <p>세션은 종료돼도 삭제하지 않는다. 측정 결과가 이 세션을 FK 로 참조하기 때문이다
 * ("어느 화물을 재던 측정인가"를 나중에도 역추적할 수 있어야 한다).
 * 지금 점유 중인 세션이 무엇인지는 {@link StationState} 한 행이 가리킨다.
 */
public class StationSession {

    private String sessionId;
    private Long cargoId;

    public StationSession() {
    }

    public StationSession(String sessionId, Long cargoId) {
        this.sessionId = sessionId;
        this.cargoId = cargoId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getCargoId() {
        return cargoId;
    }

    public void setCargoId(Long cargoId) {
        this.cargoId = cargoId;
    }
}
