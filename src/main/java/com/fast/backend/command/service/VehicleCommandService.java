package com.fast.backend.command.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandMessage;
import com.fast.backend.command.dto.VehicleCommandPayload;
import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.mapper.VehicleCommandMapper;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST로 받은 차량 명령 요청을 검증·저장하고 단일 MQTT 명령 토픽으로 발행한다
 * (prompt32.md 1장 7~11번, 3장 1~3번).
 *
 * <p><b>백엔드가 책임지는 것</b>(3장 2번): {@code commandId} 생성(UUID), {@code timestamp} 생성,
 * {@code targetSystem}/{@code commandCategory} 조합 검증, MQTT 발행 DTO 생성. 요청자는 명령 이름과
 * (필요하면) destination·reason만 보낸다.
 *
 * <p><b>MQTT 발행 성공과 실제 실행 성공을 구분한다</b>: 이 클래스는 발행 호출 성공 여부만 판단하고,
 * 실제 실행 결과는 {@link VehicleCommandResultService}가 별도로 반영한다. 발행이 실패해도 트랜잭션을
 * 롤백하지 않고 {@link VehicleCommandStatus#PUBLISH_FAILED} 상태로 정직하게 저장한다 — "MQTT 발행
 * 실패를 실행 성공으로 저장하지 않는다"는 조건은 이 상태 분리로 만족된다.
 *
 * <p><b>주의</b>: {@code PUBLISHED}는 브로커 발행 호출이 성공했다는 뜻일 뿐, ROS2/임베디드 수신이나
 * 실제 모터 정지를 보장하지 않는다. 특히 {@code EMERGENCY_STOP}의 실제 정지 여부는 이 저장소만으로
 * 검증할 수 없다(외부 연동 확인 필요).
 */
@Service
public class VehicleCommandService {

    private static final Logger log = LoggerFactory.getLogger(VehicleCommandService.class);

    public static final int DEFAULT_LIMIT = 50;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;

    /** 확정 규격: destination의 frameId는 map/odom만 허용한다(prompt32.md 1장 5번·9번). */
    public static final String DEFAULT_FRAME_ID = "map";
    public static final Set<String> ALLOWED_FRAME_IDS = Set.of("map", "odom");

    private final VehicleMapper vehicleMapper;
    private final VehicleCommandMapper commandMapper;
    private final VehicleCommandPublisher publisher;
    private final ObjectMapper objectMapper;

    public VehicleCommandService(
            VehicleMapper vehicleMapper,
            VehicleCommandMapper commandMapper,
            VehicleCommandPublisher publisher,
            ObjectMapper objectMapper) {
        this.vehicleMapper = vehicleMapper;
        this.commandMapper = commandMapper;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public VehicleCommandResponse issueCommand(String vehicleId, VehicleCommandRequest request) {
        vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));

        VehicleCommandType commandType = VehicleCommandType.fromRaw(request.command())
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_TYPE_INVALID,
                        "알 수 없는 명령입니다: " + request.command()));

        VehicleCommandTargetSystem targetSystem = resolveTargetSystem(request.targetSystem(), commandType);
        VehicleCommandCategory category = resolveCategory(request.commandCategory(), commandType);
        VehicleCommandPayload payload = buildPayload(commandType, request.destination());

        String commandId = UUID.randomUUID().toString();
        OffsetDateTime issuedAtOffset = CommunicationTime.nowOffset();
        LocalDateTime issuedAt = CommunicationTime.toLocal(issuedAtOffset);

        VehicleCommand entity = new VehicleCommand();
        entity.setCommandId(commandId);
        entity.setVehicleId(vehicleId);
        entity.setCommand(commandType);
        entity.setTargetSystem(targetSystem);
        entity.setCommandCategory(category);
        entity.setPayloadJson(serializePayload(payload));
        entity.setReason(request.reason());
        entity.setStatus(VehicleCommandStatus.PENDING);
        entity.setIssuedAt(issuedAt);
        entity.setCreatedAt(issuedAt);
        entity.setUpdatedAt(issuedAt);
        commandMapper.insert(entity);

        publishAndUpdateStatus(entity, commandType, targetSystem, category, payload, issuedAtOffset);

        return toResponse(entity);
    }

    /**
     * 요청이 조합을 명시하지 않으면 확정 조합표에서 채우고, 명시했으면 확정 조합과 일치하는지 검증한다.
     * 어긋나면 400으로 거부한다 — 호출자가 잘못 알고 있는 조합을 조용히 고쳐서 발행하면, 수신 측이
     * targetSystem으로 1차 분기하는 설계 자체가 신뢰를 잃는다(prompt32.md 1장 8번 "잘못된 조합은 발행하지
     * 말고 400 BusinessException으로 처리한다").
     */
    private VehicleCommandTargetSystem resolveTargetSystem(String raw, VehicleCommandType commandType) {
        if (raw == null || raw.isBlank()) {
            return commandType.requiredTargetSystem();
        }
        VehicleCommandTargetSystem requested = VehicleCommandTargetSystem.fromRaw(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID,
                        "알 수 없는 targetSystem입니다: " + raw));
        if (requested != commandType.requiredTargetSystem()) {
            throw new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID,
                    "명령 " + commandType + "의 targetSystem은 " + commandType.requiredTargetSystem()
                            + "여야 합니다: " + requested);
        }
        return requested;
    }

    private VehicleCommandCategory resolveCategory(String raw, VehicleCommandType commandType) {
        if (raw == null || raw.isBlank()) {
            return commandType.requiredCategory();
        }
        VehicleCommandCategory requested = VehicleCommandCategory.fromRaw(raw)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID,
                        "알 수 없는 commandCategory입니다: " + raw));
        if (requested != commandType.requiredCategory()) {
            throw new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID,
                    "명령 " + commandType + "의 commandCategory는 " + commandType.requiredCategory()
                            + "여야 합니다: " + requested);
        }
        return requested;
    }

    /**
     * MOVE 명령은 destination이 필수이며 좌표·heading·frameId를 확정 규격대로 검증한다
     * (prompt32.md 1장 9번). destination이 필요 없는 명령에 destination이 오면 조용히 무시하지 않고
     * 빈 payload로 발행한다 — 값을 버렸다는 사실을 경고 로그로 남긴다.
     */
    private VehicleCommandPayload buildPayload(VehicleCommandType commandType, VehicleCommandDestination destination) {
        if (!commandType.isDestinationRequired()) {
            if (destination != null) {
                log.warn("destination ignored for command that does not use it: command={}", commandType);
            }
            return VehicleCommandPayload.empty();
        }
        if (destination == null) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID,
                    "MOVE 명령에는 destination이 필요합니다.");
        }
        Double x = destination.x();
        Double y = destination.y();
        if (x == null || y == null || !isFinite(x) || !isFinite(y)) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID,
                    "destination.x/y는 필수이며 유한한 값이어야 합니다.");
        }
        Double heading = destination.heading();
        if (heading != null && !isFinite(heading)) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID,
                    "destination.heading은 유한한 값이어야 합니다: " + heading);
        }
        String frameId = (destination.frameId() == null || destination.frameId().isBlank())
                ? DEFAULT_FRAME_ID
                : destination.frameId().trim();
        if (!ALLOWED_FRAME_IDS.contains(frameId)) {
            throw new BusinessException(ErrorCode.COMMAND_DESTINATION_INVALID,
                    "destination.frameId는 map 또는 odom만 허용합니다: " + frameId);
        }
        return VehicleCommandPayload.ofDestination(
                new VehicleCommandDestination(x, y, normalizeHeading(heading), frameId));
    }

    /** heading(degree)을 [0,360)으로 정규화한다(prompt32.md 1장 5번). */
    private Double normalizeHeading(Double heading) {
        if (heading == null) {
            return null;
        }
        double normalized = heading % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return normalized;
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private String serializePayload(VehicleCommandPayload payload) {
        if (payload == null || payload.destination() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // payload는 백엔드가 방금 만든 단순 레코드라 실무상 실패할 수 없다. 그래도 저장 실패로
            // 명령 발행 전체를 막지는 않는다 — payload_json은 조회·감사용 부가 정보이기 때문이다.
            log.error("Failed to serialize command payload for DB: error={}", e.getMessage());
            return null;
        }
    }

    private void publishAndUpdateStatus(
            VehicleCommand entity,
            VehicleCommandType commandType,
            VehicleCommandTargetSystem targetSystem,
            VehicleCommandCategory category,
            VehicleCommandPayload payload,
            OffsetDateTime issuedAt) {
        LocalDateTime now = CommunicationTime.nowLocal();
        try {
            VehicleCommandMessage message = new VehicleCommandMessage(
                    entity.getCommandId(),
                    entity.getVehicleId(),
                    targetSystem,
                    category,
                    commandType,
                    payload,
                    entity.getReason(),
                    issuedAt);
            publisher.publish(message);
            entity.setStatus(VehicleCommandStatus.PUBLISHED);
            entity.setPublishedAt(now);
            log.info("Vehicle command published: commandId={}, vehicleId={}, command={}, targetSystem={}",
                    entity.getCommandId(), entity.getVehicleId(), commandType, targetSystem);
        } catch (RuntimeException e) {
            log.error("Failed to publish vehicle command: commandId={}, vehicleId={}, command={}, error={}",
                    entity.getCommandId(), entity.getVehicleId(), commandType, e.getMessage());
            entity.setStatus(VehicleCommandStatus.PUBLISH_FAILED);
        }
        entity.setUpdatedAt(now);
        commandMapper.update(entity);
    }

    @Transactional(readOnly = true)
    public VehicleCommandResponse findByCommandId(String vehicleId, String commandId) {
        VehicleCommand entity = commandMapper.findByCommandId(commandId)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_NOT_FOUND,
                        "존재하지 않는 명령입니다: " + commandId));
        if (!entity.getVehicleId().equals(vehicleId)) {
            throw new BusinessException(ErrorCode.COMMAND_NOT_FOUND,
                    "해당 차량의 명령이 아닙니다: " + commandId);
        }
        return toResponse(entity);
    }

    /** 분류 필터 없이 최근 명령을 조회한다(기존 호출 계약 유지). */
    @Transactional(readOnly = true)
    public List<VehicleCommandResponse> findRecentByVehicleId(String vehicleId, int limit) {
        return findRecentByVehicleId(vehicleId, limit, null);
    }

    /**
     * 차량별 최근 명령 조회. {@code rawCategory}가 주어지면 해당 {@link VehicleCommandCategory}만 반환한다
     * (prompt56.md 12장 A안).
     *
     * <p>이 필터를 둔 이유: 관제 화면이 "최근 안전 명령 상태"를 보여줄 때 필터 없이 최신 1건을 뽑으면
     * 직전에 발행된 {@code MOVE}/{@code FORK} 명령이 안전 명령인 것처럼 표시된다. {@code category=SAFETY}로
     * 물으면 STOP/EMERGENCY_STOP/RESET_ESTOP만 걸러진다.
     *
     * <p>알 수 없는 분류 문자열은 조용히 무시하지 않고 400으로 거부한다 — 오타 하나 때문에 "전체 명령"이
     * 반환되면 호출자가 잘못된 값을 안전 명령으로 오해할 수 있기 때문이다.
     */
    @Transactional(readOnly = true)
    public List<VehicleCommandResponse> findRecentByVehicleId(String vehicleId, int limit, String rawCategory) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.COMMAND_LIMIT_INVALID,
                    "limit은 " + MIN_LIMIT + "~" + MAX_LIMIT + " 범위여야 합니다: " + limit);
        }
        VehicleCommandCategory category = resolveFilterCategory(rawCategory);
        vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));
        return commandMapper.findRecentByVehicleId(vehicleId, limit, category).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private VehicleCommandCategory resolveFilterCategory(String rawCategory) {
        if (rawCategory == null || rawCategory.isBlank()) {
            return null; // 필터 없음 = 전체 분류
        }
        return VehicleCommandCategory.fromRaw(rawCategory)
                .orElseThrow(() -> new BusinessException(ErrorCode.COMMAND_COMBINATION_INVALID,
                        "알 수 없는 commandCategory입니다: " + rawCategory));
    }

    private VehicleCommandResponse toResponse(VehicleCommand entity) {
        return new VehicleCommandResponse(
                entity.getCommandId(),
                entity.getVehicleId(),
                entity.getCommand() != null ? entity.getCommand().name() : null,
                entity.getTargetSystem() != null ? entity.getTargetSystem().name() : null,
                entity.getCommandCategory() != null ? entity.getCommandCategory().name() : null,
                entity.getStatus() != null ? entity.getStatus().name() : null,
                entity.getReason(),
                entity.getPayloadJson(),
                CommunicationTime.toOffset(entity.getIssuedAt()),
                CommunicationTime.toOffset(entity.getPublishedAt()),
                CommunicationTime.toOffset(entity.getCompletedAt()),
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
