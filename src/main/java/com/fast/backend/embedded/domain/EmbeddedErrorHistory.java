package com.fast.backend.embedded.domain;

import java.time.LocalDateTime;

/** {@code embedded_error_history} 테이블 한 행(prompt29.md 17장) — 오류 이벤트 누적 이력. */
public class EmbeddedErrorHistory {

    private Long id;
    private String forkliftId;
    private String errorCode;
    private EmbeddedErrorSource errorSource;
    private EmbeddedErrorSeverity severity;
    private String message;
    private LocalDateTime occurredAt;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;

    public EmbeddedErrorHistory() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getForkliftId() {
        return forkliftId;
    }

    public void setForkliftId(String forkliftId) {
        this.forkliftId = forkliftId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public EmbeddedErrorSource getErrorSource() {
        return errorSource;
    }

    public void setErrorSource(EmbeddedErrorSource errorSource) {
        this.errorSource = errorSource;
    }

    public EmbeddedErrorSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(EmbeddedErrorSeverity severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
