package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.websocket.VehicleWebSocketBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/**
 * 위치 메시지 시각을 변경하지 않고 저주기 차량 상태를 저장한다.
 *
 * <p>차량당 <b>현재 상태 1행</b>({@code vehicle_current_status})만 유지하며 과거 이력 테이블은 두지 않는다.
 * 관제 화면·대시보드·미니맵 어느 것도 이력을 소비하지 않는데 상태 메시지마다 INSERT가 쌓였고, 그 INSERT
 * 실패가 같은 트랜잭션의 현재 상태 저장까지 롤백시켜 실시간 관제를 멈추는 구조였다(팀 합의로 제거).
 */
@Service
public class VehicleStatusService {

    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper statusMapper;
    private final VehicleWebSocketBroadcaster broadcaster;

    public VehicleStatusService(
            VehicleMapper vehicleMapper,
            VehicleCurrentStatusMapper statusMapper,
            VehicleWebSocketBroadcaster broadcaster) {
        this.vehicleMapper = vehicleMapper;
        this.statusMapper = statusMapper;
        this.broadcaster = broadcaster;
    }

    @Transactional
    public VehicleStatusResponse updateCurrentStatus(String vehicleId, VehicleStatusUpdateCommand command) {
        if (!vehicleMapper.existsByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: " + vehicleId);
        }

        LocalDateTime receivedAt = CommunicationTime.nowLocal();
        VehicleCurrentStatus update = new VehicleCurrentStatus();
        update.setVehicleId(vehicleId);
        VehicleStatus currentStatus = command.status() == null
                ? statusMapper.findByVehicleId(vehicleId)
                        .map(VehicleCurrentStatus::getStatus)
                        .orElse(VehicleStatus.UNKNOWN)
                : VehicleStatus.fromRaw(command.status());
        update.setStatus(currentStatus == null ? VehicleStatus.UNKNOWN : currentStatus);
        update.setHasCargo(command.hasCargo());
        // hasCargo=false는 센서가 "비어 있음"을 확정한 값이므로 이전 cargoId를 반드시 지운다.
        update.setCargoId(Boolean.FALSE.equals(command.hasCargo()) ? null : command.cargoId());
        update.setReceivedAt(receivedAt);
        statusMapper.upsert(update);

        VehicleCurrentStatus stored = statusMapper.findByVehicleId(vehicleId).orElse(update);
        VehicleStatusResponse response = toResponse(stored);
        broadcaster.broadcastStatus(vehicleId, response,
                command.messageAt() != null ? command.messageAt() : CommunicationTime.toOffset(receivedAt));
        return response;
    }

    private VehicleStatusResponse toResponse(VehicleCurrentStatus status) {
        return new VehicleStatusResponse(
                status.getStatus(), status.getPositionX(), status.getPositionY(),
                status.getPositionFrame(), status.getHeading(), status.getSpeed(),
                status.getHasCargo(), status.getCargoId(),
                CommunicationTime.toOffset(status.getMessageAt()),
                CommunicationTime.toOffset(status.getReceivedAt()));
    }
}
