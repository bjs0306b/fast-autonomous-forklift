package com.fast.backend.command.service;

import com.fast.backend.command.dto.EmergencyStopAllResponse;
import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.command.websocket.SafetyCommandBroadcaster;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 차량 안전 제어(STOP/EMERGENCY_STOP) 조율.
 *
 * <p><b>기존 {@link VehicleCommandService}를 재사용</b>한다(§6 "기존 VehicleCommand 영속 구조가 있으면 반드시
 * 재사용"). 단건 STOP/EMERGENCY_STOP의 저장·발행·상태(PUBLISHED/PUBLISH_FAILED)·commandId 생성·결과 반영은
 * 이미 그 도메인이 담당하므로 새 테이블을 만들지 않는다. 이 서비스는 그 위에 (1) vehicleId 형식·활성 검증,
 * (2) 전체 비상정지 fan-out(차량별 독립 commandId·부분 실패), (3) 발행 시점 WebSocket 이벤트만 더한다.
 *
 * <p>이 서비스는 트랜잭션을 열지 않는다 — 각 {@code issueCommand} 호출이 자체 트랜잭션으로 커밋되어야
 * 한 차량 실패가 다른 차량 발행을 롤백하지 않기 때문이다(§9). 발행이 커밋된 뒤 WebSocket을 전송한다.
 */
@Service
public class SafetyCommandService {

    private static final Logger log = LoggerFactory.getLogger(SafetyCommandService.class);

    private static final String STOP = "STOP";
    private static final String EMERGENCY_STOP = "EMERGENCY_STOP";

    private final VehicleMapper vehicleMapper;
    private final VehicleCommandService vehicleCommandService;
    private final SafetyCommandBroadcaster broadcaster;

    public SafetyCommandService(
            VehicleMapper vehicleMapper, VehicleCommandService vehicleCommandService,
            SafetyCommandBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCommandService = vehicleCommandService;
        this.broadcaster = broadcaster;
    }

    public VehicleCommandResponse stop(String vehicleId) {
        return issueSafety(vehicleId, STOP);
    }

    public VehicleCommandResponse emergencyStop(String vehicleId) {
        return issueSafety(vehicleId, EMERGENCY_STOP);
    }

    private VehicleCommandResponse issueSafety(String vehicleId, String command) {
        validateVehicleId(vehicleId);
        requireActiveVehicle(vehicleId);
        VehicleCommandResponse response = vehicleCommandService.issueCommand(
                vehicleId, new VehicleCommandRequest(command, null, null, null, null));
        broadcaster.broadcastPublish(command, response);
        return response;
    }

    /**
     * 활성 차량 전체에 EMERGENCY_STOP을 차량별로 개별 발행한다. 차량마다 별도 commandId·DB 레코드가
     * 생성되며(issueCommand 재사용), 한 차량의 발행 실패가 다음 차량 처리를 막지 않는다.
     */
    public EmergencyStopAllResponse emergencyStopAll() {
        List<Vehicle> activeVehicles = vehicleMapper.findAllActive(); // 활성·미삭제 차량, vehicleId 오름차순
        List<EmergencyStopAllResponse.Item> results = new ArrayList<>();
        int published = 0;
        int failed = 0;

        for (Vehicle vehicle : activeVehicles) {
            String vehicleId = vehicle.getVehicleId();
            try {
                VehicleCommandResponse response = vehicleCommandService.issueCommand(
                        vehicleId, new VehicleCommandRequest(EMERGENCY_STOP, null, null, null, null));
                broadcaster.broadcastPublish(EMERGENCY_STOP, response);
                boolean ok = "PUBLISHED".equals(response.status());
                if (ok) {
                    published++;
                } else {
                    failed++;
                }
                results.add(new EmergencyStopAllResponse.Item(
                        vehicleId, response.commandId(), EMERGENCY_STOP, response.status(),
                        ok ? null : "MQTT publish failed"));
            } catch (RuntimeException e) {
                // issueCommand는 발행 실패를 PUBLISH_FAILED로 흡수하지만, 예상 못한 예외도 한 차량에서
                // 멈추지 않도록 방어한다(§9 "한 차량 예외로 반복문 전체 중단 금지").
                log.error("Emergency-stop-all failed for vehicle {}: {}", vehicleId, e.getMessage());
                failed++;
                results.add(new EmergencyStopAllResponse.Item(
                        vehicleId, null, EMERGENCY_STOP, "PUBLISH_FAILED", e.getMessage()));
            }
        }

        broadcaster.broadcastGlobalSummary(activeVehicles.size(), published, failed);
        log.info("Emergency-stop-all: requested={}, published={}, failed={}",
                activeVehicles.size(), published, failed);
        return new EmergencyStopAllResponse(activeVehicles.size(), published, failed, results);
    }

    /** MQTT 토픽 인젝션 방지(§5·§15): 공백·{@code / # +} 등 와일드카드/구분 문자를 차단한다. */
    private void validateVehicleId(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()
                || vehicleId.chars().anyMatch(c -> c == '/' || c == '#' || c == '+'
                || Character.isWhitespace(c))) {
            throw new BusinessException(ErrorCode.INVALID_VEHICLE_ID, "vehicleId 형식이 올바르지 않습니다: " + vehicleId);
        }
    }

    private void requireActiveVehicle(String vehicleId) {
        Vehicle vehicle = vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));
        if (!vehicle.isActive()) {
            throw new BusinessException(ErrorCode.VEHICLE_INACTIVE, "비활성 차량입니다: " + vehicleId);
        }
    }
}
