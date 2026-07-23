package com.fast.backend.embedded.domain;

import java.time.LocalDateTime;

/**
 * {@code vehicle_fork_current_status} 테이블 한 행(prompt29.md 17장) — 차량당 최신 포크 상태 1행만
 * 유지한다(forklift_id가 PK). {@code GET /api/vehicles/{forkliftId}/fork-status} 조회를 WebSocket
 * 없이도 서빙하려면 최소한의 현재값 저장이 필요하다고 판단했다(17장 "시연에서 WebSocket 전달만으로
 * 충분한지 먼저 판단" — REST 조회 API가 요구사항에 명시돼 있어(18장) 저장하기로 결정).
 */
public class VehicleForkCurrentStatus {

    private String forkliftId;
    private EmbeddedForkState forkState;
    private Boolean limitBottom;
    private String errorCode;
    private LocalDateTime messageAt;
    private LocalDateTime receivedAt;
    private LocalDateTime updatedAt;

    public VehicleForkCurrentStatus() {
    }

    public String getForkliftId() {
        return forkliftId;
    }

    public void setForkliftId(String forkliftId) {
        this.forkliftId = forkliftId;
    }

    public EmbeddedForkState getForkState() {
        return forkState;
    }

    public void setForkState(EmbeddedForkState forkState) {
        this.forkState = forkState;
    }

    public Boolean getLimitBottom() {
        return limitBottom;
    }

    public void setLimitBottom(Boolean limitBottom) {
        this.limitBottom = limitBottom;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public LocalDateTime getMessageAt() {
        return messageAt;
    }

    public void setMessageAt(LocalDateTime messageAt) {
        this.messageAt = messageAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
