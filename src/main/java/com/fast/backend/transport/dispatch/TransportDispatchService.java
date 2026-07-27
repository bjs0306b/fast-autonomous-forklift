package com.fast.backend.transport.dispatch;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.dto.TransportCommandMessage;
import com.fast.backend.transport.dto.TransportDispatchResponse;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 수동 배정된 Task의 MQTT 디스패치 오케스트레이터(prompt48.md 8·9장). 트랜잭션 경계를 넘나드는 순서를
 * 조율한다 — <b>자신은 트랜잭션이 아니다</b>. DB 단계는 {@link TransportDispatchTxService}의
 * {@code @Transactional} 메서드가 각각 커밋한다.
 *
 * <p>흐름: CREATED 저장(커밋) → MQTT publish → 성공 시 PUBLISHED + Task 시작 상태(커밋), 실패 시
 * PUBLISH_FAILED(커밋, Task는 ASSIGNED 유지). DB insert와 publish는 하나의 원자적 트랜잭션이 아니므로,
 * "publish 성공 후 상태 반영 실패"는 남을 수 있어 위험을 보고서에 명시한다(§9, Outbox는 도입하지 않음).
 */
@Service
public class TransportDispatchService {

    private static final Logger log = LoggerFactory.getLogger(TransportDispatchService.class);

    private final TransportDispatchTxService txService;
    private final TransportCommandPublisher publisher;
    private final TransportCommandMapper transportCommandMapper;
    private final TransportTaskMapper transportTaskMapper;

    public TransportDispatchService(
            TransportDispatchTxService txService, TransportCommandPublisher publisher,
            TransportCommandMapper transportCommandMapper, TransportTaskMapper transportTaskMapper) {
        this.txService = txService;
        this.publisher = publisher;
        this.transportCommandMapper = transportCommandMapper;
        this.transportTaskMapper = transportTaskMapper;
    }

    public TransportDispatchResponse dispatch(String taskCode) {
        TransportCommandMessage message = txService.createCommand(taskCode); // CREATED 커밋

        try {
            publisher.publish(message);
        } catch (RuntimeException e) {
            log.warn("Transport command publish failed: commandId={}, reason={}",
                    message.commandId(), e.getMessage());
            txService.markPublishFailed(message.commandId(), e.getMessage()); // PUBLISH_FAILED 커밋
            throw new BusinessException(ErrorCode.MQTT_DISPATCH_FAILED,
                    "MQTT 발행에 실패했습니다: " + e.getMessage());
        }

        txService.confirmPublished(message.commandId(), taskCode); // PUBLISHED + Task 시작 상태 커밋
        log.info("Transport task dispatched: taskCode={}, commandId={}", taskCode, message.commandId());
        return buildResponse(taskCode, message.commandId());
    }

    private TransportDispatchResponse buildResponse(String taskCode, String commandId) {
        TransportCommand command = transportCommandMapper.findByCommandId(commandId).orElse(null);
        TransportTask task = transportTaskMapper.findByTaskCode(taskCode).orElse(null);
        return new TransportDispatchResponse(
                taskCode,
                commandId,
                command == null ? null : command.getStatus().name(),
                task == null ? null : task.getStatus().name(),
                task == null ? null : task.getVehicleId());
    }
}
