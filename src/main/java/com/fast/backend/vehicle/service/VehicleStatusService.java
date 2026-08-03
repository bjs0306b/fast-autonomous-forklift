package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/**
 * 위치 메시지 시각을 변경하지 않고 저주기 차량 상태를 저장한다.
 *
 * <p>정상 처리된 상태 메시지는 <b>최신 상태({@code vehicle_current_status}) 갱신과 이력
 * ({@code vehicle_status_history}) 저장을 같은 트랜잭션에서</b> 함께 수행한다(Jira -132). 검증에
 * 실패했거나 미등록 차량이면 두 저장 모두 일어나지 않는다 — 검증이 upsert보다 먼저 예외를 던지기 때문이다.
 */
@Service
public class VehicleStatusService {

    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper statusMapper;
    private final VehicleStatusHistoryMapper historyMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public VehicleStatusService(
            VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper statusMapper,
            VehicleStatusHistoryMapper historyMapper,
            VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.statusMapper = statusMapper;
        this.historyMapper = historyMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public VehicleStatusResponse updateCurrentStatus(String vehicleId, VehicleStatusUpdateCommand command) {
        if (!vehicleMapper.existsByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: " + vehicleId);
        }
        if (command.battery() != null && (command.battery() < 0 || command.battery() > 100)) {
            throw new BusinessException(ErrorCode.VEHICLE_BATTERY_OUT_OF_RANGE,
                    "battery는 0~100 범위여야 합니다: " + command.battery());
        }

        LocalDateTime receivedAt = CommunicationTime.nowLocal();
        VehicleStatus status = VehicleStatus.fromRaw(command.status());
        VehicleCurrentStatus update = new VehicleCurrentStatus();
        update.setVehicleId(vehicleId);
        update.setStatus(status);
        update.setBattery(command.battery());
        update.setReceivedAt(receivedAt);
        statusMapper.upsert(update);
        appendHistory(vehicleId, status, command, receivedAt);

        VehicleCurrentStatus stored = statusMapper.findByVehicleId(vehicleId).orElse(update);
        VehicleStatusResponse response = toResponse(stored);
        broadcaster.broadcastStatus(vehicleId, response,
                command.messageAt() != null ? command.messageAt() : CommunicationTime.toOffset(receivedAt));
        return response;
    }

    /**
     * 정상 처리된 상태 메시지를 이력으로 남긴다(Jira -132).
     *
     * <p><b>직전과 같은 상태여도 저장한다.</b> ROS2 브리지는 상태가 바뀌지 않아도 1초마다 heartbeat를
     * 재발행하고({@code fast_mqtt_bridge}의 {@code heartbeat_interval_ms}, {@code publish_heartbeat}),
     * 그 heartbeat는 "차량이 그 시각에 살아 있었다"는 별개의 사실이다. 같은 상태라는 이유로 건너뛰면
     * 통신 두절 구간과 상태 유지 구간을 이력에서 구분할 수 없게 된다.
     *
     * <p>상태 메시지에는 고유 식별자가 없어 <b>UNIQUE 제약을 두지 않았다</b>. MQTT QoS 1 재전송으로
     * 완전히 같은 메시지가 두 번 들어오면 이력도 두 건이 된다 — heartbeat마다 {@code timestamp}가 새로
     * 찍히므로 실제로 값이 완전히 같은 중복은 재전송에서만 생기고, 그 드문 중복을 막으려고 제약을 걸면
     * 정상 heartbeat까지 누락되기 때문이다(남은 확인 사항 참고).
     */
    private void appendHistory(
            String vehicleId, VehicleStatus status, VehicleStatusUpdateCommand command, LocalDateTime receivedAt) {
        VehicleStatusHistory history = new VehicleStatusHistory();
        history.setVehicleId(vehicleId);
        history.setStatus(status);
        history.setBattery(command.battery());
        history.setMessageAt(CommunicationTime.toLocal(command.messageAt()));
        history.setReceivedAt(receivedAt);
        historyMapper.insert(history);
    }

    private VehicleStatusResponse toResponse(VehicleCurrentStatus status) {
        return new VehicleStatusResponse(
                status.getStatus(), status.getBattery(), status.getPositionX(), status.getPositionY(),
                status.getPositionFrame(), status.getHeading(), status.getSpeed(),
                status.getHasCargo(), status.getCargoId(),
                CommunicationTime.toOffset(status.getMessageAt()),
                CommunicationTime.toOffset(status.getReceivedAt()));
    }
}
