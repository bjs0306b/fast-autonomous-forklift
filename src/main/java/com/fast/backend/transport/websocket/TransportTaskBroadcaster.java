package com.fast.backend.transport.websocket;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.transport.domain.TaskFailureCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 운반 작업 이벤트 STOMP 브로드캐스트(prompt48.md 17장). 기존 {@code VehicleWebSocketBroadcaster} 패턴을
 * 따라 전체·작업별 두 토픽으로 보내고, 전송 실패는 로그만 남긴다(이미 커밋된 DB 결과에 영향 없음).
 *
 * <p><b>DB 커밋 이후에만 전송</b>한다({@link VehicleWebSocketBroadcaster}와 동일 정책): 트랜잭션이 활성이면
 * afterCommit에 등록하고, 아니면 즉시 보낸다.
 */
@Component
public class TransportTaskBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TransportTaskBroadcaster.class);

    static final String TOPIC_ALL = "/topic/transport-tasks";

    private final SimpMessagingTemplate messagingTemplate;

    public TransportTaskBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** 커밋 이후(트랜잭션 활성 시) 또는 즉시 전송한다. */
    public void broadcastAfterCommit(String eventType, String taskId, String status, String vehicleId) {
        broadcastAfterCommit(eventType, taskId, status, vehicleId, null);
    }

    /** 실패 원인 코드를 함께 싣는다. 실패가 아닌 이벤트는 {@code failureCode} 가 null 이다. */
    public void broadcastAfterCommit(String eventType, String taskId, String status, String vehicleId,
            TaskFailureCode failureCode) {
        TransportTaskEvent event = new TransportTaskEvent(
                eventType, taskId, status, vehicleId,
                failureCode == null ? null : failureCode.name(), CommunicationTime.nowOffset());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(event);
            }
        });
    }

    private void send(TransportTaskEvent event) {
        try {
            messagingTemplate.convertAndSend(TOPIC_ALL, event);
            messagingTemplate.convertAndSend(TOPIC_ALL + "/" + event.taskId(), event);
            log.debug("Transport task event broadcast: eventType={}, taskId={}", event.eventType(), event.taskId());
        } catch (Exception e) {
            log.error("Failed to broadcast transport task event: taskId={}, error={}",
                    event.taskId(), e.getMessage());
        }
    }
}
