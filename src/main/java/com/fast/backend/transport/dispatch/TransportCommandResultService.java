package com.fast.backend.transport.dispatch;

import com.fast.backend.command.dto.VehicleCommandResultMessage;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.mapper.TransportCommandMapper;
import com.fast.backend.transport.service.TransportTaskService;
import com.fast.backend.transport.websocket.TransportTaskBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * {@code forklift/{vehicleId}/command-result}로 수신된 결과를 <b>운반 명령</b>에 반영한다(prompt48.md 12~16장).
 * 기존 {@code VehicleCommandResultMessage}(공통 결과 envelope)를 그대로 재사용한다.
 *
 * <p>이 서비스는 자기 소유(commandId가 transport_command에 존재하는) 결과만 처리하고, 그 외(기존 차량 명령
 * 결과)는 조용히 무시한다 — {@code MqttMessageRouter}가 차량 명령 결과 서비스와 이 서비스를 함께 호출하며,
 * 각자 자기 commandId만 처리한다(transport commandId는 {@code TCMD-} 접두어).
 *
 * <p><b>멱등성</b>: 이미 종료된 command면 무시하고, 상태 전이는 조건부 UPDATE(count=1)로만 반영한다. 성공/실패
 * 결과의 Task/Slot/Pallet 반영은 기존 {@link TransportTaskService#driveToCompleted}/{@code driveToFailed}를
 * 재사용해 완료 로직을 복제하지 않는다.
 */
@Service
public class TransportCommandResultService {

    private static final Logger log = LoggerFactory.getLogger(TransportCommandResultService.class);

    private static final Set<String> SUCCESS_VALUES = Set.of("SUCCESS", "SUCCEEDED", "OK", "COMPLETED");
    private static final Set<String> FAIL_VALUES = Set.of("FAIL", "FAILED", "ERROR");

    private final TransportCommandMapper transportCommandMapper;
    private final TransportTaskService transportTaskService;
    private final TransportTaskBroadcaster broadcaster;

    public TransportCommandResultService(
            TransportCommandMapper transportCommandMapper, TransportTaskService transportTaskService,
            TransportTaskBroadcaster broadcaster) {
        this.transportCommandMapper = transportCommandMapper;
        this.transportTaskService = transportTaskService;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public void handleResult(VehicleCommandResultMessage message) {
        if (message == null || message.commandId() == null || message.commandId().isBlank()) {
            // commandId가 없으면 Task를 추측하지 않는다(prompt48.md 12·16장).
            return;
        }
        // 자기 소유가 아닌 결과(차량 명령 결과 등)는 조용히 무시 — commandId가 transport_command에 없다.
        TransportCommand command = transportCommandMapper.findByCommandId(message.commandId()).orElse(null);
        if (command == null) {
            return;
        }

        // vehicleId 불일치 → 폐기 + 오류 로그(prompt48.md 12·16장). consumer는 죽이지 않는다.
        if (message.vehicleId() == null || !command.getVehicleId().equals(message.vehicleId())) {
            log.error("Transport command result discarded, vehicleId mismatch: commandId={}, expected={}, actual={}",
                    message.commandId(), command.getVehicleId(), message.vehicleId());
            return;
        }

        // 이미 종료된 command면 중복 결과로 보고 무시(멱등성).
        if (command.getStatus().isTerminal()) {
            log.info("Transport command result ignored, already terminal: commandId={}, status={}",
                    message.commandId(), command.getStatus());
            return;
        }

        String result = message.result() == null ? "" : message.result().trim().toUpperCase();
        LocalDateTime completedAt = message.completedAt() != null
                ? CommunicationTime.toLocal(message.completedAt()) : LocalDateTime.now();
        LocalDateTime now = LocalDateTime.now();

        if (SUCCESS_VALUES.contains(result)) {
            int updated = transportCommandMapper.markSucceeded(message.commandId(), completedAt, now);
            if (updated != 1) {
                log.info("Transport SUCCESS result is a duplicate/no-op: commandId={}", message.commandId());
                return;
            }
            transportTaskService.driveToCompleted(command.getTaskCode());
            broadcaster.broadcastAfterCommit("TASK_COMPLETED", command.getTaskCode(), "COMPLETED",
                    command.getVehicleId());
        } else if (FAIL_VALUES.contains(result)) {
            int updated = transportCommandMapper.markFailed(message.commandId(), message.message(), completedAt, now);
            if (updated != 1) {
                log.info("Transport FAIL result is a duplicate/no-op: commandId={}", message.commandId());
                return;
            }
            transportTaskService.driveToFailed(command.getTaskCode());
            broadcaster.broadcastAfterCommit("TASK_FAILED", command.getTaskCode(), "FAILED",
                    command.getVehicleId());
        } else {
            log.warn("Transport command result skipped, unknown result value: commandId={}, result={}",
                    message.commandId(), message.result());
        }
    }
}
