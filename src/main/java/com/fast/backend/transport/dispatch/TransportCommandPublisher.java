package com.fast.backend.transport.dispatch;

import com.fast.backend.transport.dto.TransportCommandMessage;

/**
 * 운반 명령 MQTT 발행 추상화(prompt48.md 5·19장). 발행 성공 시 정상 반환하고, 실패(비활성 포함) 시
 * 예외를 던진다 — 호출자({@code TransportDispatchService})는 예외 여부로 PUBLISHED/PUBLISH_FAILED를 판정한다.
 * 테스트에서는 이 인터페이스를 mock으로 대체해 실제 Broker 없이 성공·실패 경로를 검증한다.
 */
public interface TransportCommandPublisher {

    void publish(TransportCommandMessage message);
}
