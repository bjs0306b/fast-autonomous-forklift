package com.fast.backend.transport.domain;

/**
 * 차량에 발행한 MQTT 운반 명령의 수명 상태(prompt48.md 6장).
 *
 * <ul>
 *   <li>{@link #CREATED} — DB에 기록됨(아직 발행 전)</li>
 *   <li>{@link #PUBLISHED} — MQTT 발행 성공</li>
 *   <li>{@link #ACKNOWLEDGED} — 차량이 수신 확인(중간 결과)</li>
 *   <li>{@link #SUCCEEDED} / {@link #FAILED} — 최종 결과</li>
 *   <li>{@link #PUBLISH_FAILED} — MQTT 발행 자체 실패</li>
 *   <li>{@link #TIMEOUT} — 결과 미수신 만료(스케줄러는 이번 범위 아님, 상태값만 정의)</li>
 * </ul>
 *
 * <p>종료 상태: {@link #SUCCEEDED}/{@link #FAILED}/{@link #PUBLISH_FAILED}/{@link #TIMEOUT}.
 * 종료 상태의 command에 결과가 다시 오면 중복으로 보고 재처리하지 않는다(멱등성, 15장).
 */
public enum TransportCommandStatus {
    CREATED,
    PUBLISHED,
    ACKNOWLEDGED,
    SUCCEEDED,
    FAILED,
    PUBLISH_FAILED,
    TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == PUBLISH_FAILED || this == TIMEOUT;
    }
}
