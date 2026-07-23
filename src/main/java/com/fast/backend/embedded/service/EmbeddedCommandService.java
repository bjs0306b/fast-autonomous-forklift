package com.fast.backend.embedded.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.domain.EmbeddedCommandStatus;
import com.fast.backend.embedded.domain.EmbeddedCommandType;
import com.fast.backend.embedded.domain.EmbeddedVehicleCommand;
import com.fast.backend.embedded.dto.EmbeddedCommandResponse;
import com.fast.backend.embedded.dto.EmbeddedForkliftCommandMessage;
import com.fast.backend.embedded.mapper.EmbeddedVehicleCommandMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST로 받은 실물 지게차 명령 요청을 검증·저장하고 MQTT로 발행한다(prompt29.md 14장·16장
 * {@code EmbeddedCommandService}). {@code commandId}는 UUID로 생성한다("형식을 임의로 복잡하게
 * 만들지 않는다", 14장) — {@code CMD-20260722-001} 같은 표시용 포맷은 예시일 뿐 강제 규격이 아니다.
 *
 * <p>MQTT 발행 성공과 실제 실행 성공을 구분한다(5장 검증) — 이 클래스는 발행 성공 여부만 판단하고,
 * 실제 실행 결과는 {@link EmbeddedCommandResultService}가 별도로 반영한다. 발행이 실패해도 트랜잭션을
 * 롤백하지 않고 {@link EmbeddedCommandStatus#PUBLISH_FAILED} 상태로 정직하게 저장한다 — "MQTT 발행
 * 실패 시 실행 성공으로 저장 금지" 조건은 이 상태 분리로 만족된다.
 */
@Service
public class EmbeddedCommandService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedCommandService.class);
    public static final int DEFAULT_LIMIT = 50;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;

    private final VehicleMapper vehicleMapper;
    private final EmbeddedVehicleCommandMapper commandMapper;
    private final EmbeddedForkliftCommandPublisher publisher;

    public EmbeddedCommandService(VehicleMapper vehicleMapper, EmbeddedVehicleCommandMapper commandMapper,
            EmbeddedForkliftCommandPublisher publisher) {
        this.vehicleMapper = vehicleMapper;
        this.commandMapper = commandMapper;
        this.publisher = publisher;
    }

    @Transactional
    public EmbeddedCommandResponse issueCommand(String forkliftId, String rawCommand, String reason) {
        vehicleMapper.findByVehicleId(forkliftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + forkliftId));
        EmbeddedCommandType commandType = EmbeddedCommandType.fromRaw(rawCommand)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMBEDDED_COMMAND_TYPE_INVALID,
                        "알 수 없는 명령입니다: " + rawCommand));

        String commandId = UUID.randomUUID().toString();
        LocalDateTime issuedAt = LocalDateTime.now();

        EmbeddedVehicleCommand entity = new EmbeddedVehicleCommand();
        entity.setCommandId(commandId);
        entity.setForkliftId(forkliftId);
        entity.setCommand(commandType);
        entity.setReason(reason);
        entity.setStatus(EmbeddedCommandStatus.PENDING);
        entity.setIssuedAt(issuedAt);
        entity.setCreatedAt(issuedAt);
        entity.setUpdatedAt(issuedAt);
        commandMapper.insert(entity);

        publishAndUpdateStatus(entity, commandType, reason, issuedAt);

        return toResponse(entity);
    }

    private void publishAndUpdateStatus(
            EmbeddedVehicleCommand entity, EmbeddedCommandType commandType, String reason, LocalDateTime issuedAt) {
        LocalDateTime now = LocalDateTime.now();
        try {
            EmbeddedForkliftCommandMessage message = new EmbeddedForkliftCommandMessage(
                    entity.getCommandId(), entity.getForkliftId(), commandType.name(), reason, issuedAt);
            publisher.publish(message);
            entity.setStatus(EmbeddedCommandStatus.PUBLISHED);
            entity.setPublishedAt(now);
        } catch (RuntimeException e) {
            log.error("Failed to publish embedded command: commandId={}, forkliftId={}, error={}",
                    entity.getCommandId(), entity.getForkliftId(), e.getMessage());
            entity.setStatus(EmbeddedCommandStatus.PUBLISH_FAILED);
        }
        entity.setUpdatedAt(now);
        commandMapper.update(entity);
    }

    @Transactional(readOnly = true)
    public EmbeddedCommandResponse findByCommandId(String forkliftId, String commandId) {
        EmbeddedVehicleCommand entity = commandMapper.findByCommandId(commandId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMBEDDED_COMMAND_NOT_FOUND,
                        "존재하지 않는 명령입니다: " + commandId));
        if (!entity.getForkliftId().equals(forkliftId)) {
            throw new BusinessException(ErrorCode.EMBEDDED_COMMAND_NOT_FOUND,
                    "해당 차량의 명령이 아닙니다: " + commandId);
        }
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public List<EmbeddedCommandResponse> findRecentByForkliftId(String forkliftId, int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.EMBEDDED_COMMAND_LIMIT_INVALID,
                    "limit은 " + MIN_LIMIT + "~" + MAX_LIMIT + " 범위여야 합니다: " + limit);
        }
        vehicleMapper.findByVehicleId(forkliftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + forkliftId));
        return commandMapper.findRecentByForkliftId(forkliftId, limit).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private EmbeddedCommandResponse toResponse(EmbeddedVehicleCommand entity) {
        return new EmbeddedCommandResponse(
                entity.getCommandId(),
                entity.getForkliftId(),
                entity.getCommand() != null ? entity.getCommand().name() : null,
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getReason(),
                entity.getIssuedAt(),
                entity.getPublishedAt(),
                entity.getCompletedAt(),
                entity.getErrorCode(),
                entity.getResultMessage(),
                splitStoppedActions(entity.getStoppedActions()),
                entity.getEmergencyStopApplied(),
                entity.getRequiresReset());
    }

    private List<String> splitStoppedActions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }
}
