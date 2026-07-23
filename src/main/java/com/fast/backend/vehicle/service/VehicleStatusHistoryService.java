package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryResponse;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 차량별 상태 이력 조회 전용 Service(prompt22.md 5장). 이력 "저장"은
 * {@link VehicleStatusService#updateCurrentStatus}가 최신 상태 upsert와 같은 트랜잭션에서 처리하고,
 * 이 클래스는 조회 책임만 가진다 — VehicleService에 조회 기능을 얹으면 그 클래스의 책임(차량 기본
 * 정보 등록·조회·집계)이 계속 커지므로 별도로 분리했다.
 */
@Service
public class VehicleStatusHistoryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 200;

    private final VehicleMapper vehicleMapper;
    private final VehicleStatusHistoryMapper vehicleStatusHistoryMapper;

    public VehicleStatusHistoryService(
            VehicleMapper vehicleMapper,
            VehicleStatusHistoryMapper vehicleStatusHistoryMapper) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleStatusHistoryMapper = vehicleStatusHistoryMapper;
    }

    @Transactional(readOnly = true)
    public List<VehicleStatusHistoryResponse> findRecentHistory(String vehicleId, int limit) {
        validateLimit(limit);
        vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));

        return vehicleStatusHistoryMapper.findRecentByVehicleId(vehicleId, limit).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private void validateLimit(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException(ErrorCode.VEHICLE_STATUS_HISTORY_LIMIT_INVALID,
                    "limit은 " + MIN_LIMIT + "~" + MAX_LIMIT + " 범위여야 합니다: " + limit);
        }
    }

    private VehicleStatusHistoryResponse toResponse(VehicleStatusHistory history) {
        return new VehicleStatusHistoryResponse(
                history.getId(),
                history.getVehicleId(),
                history.getStatus(),
                history.getBattery(),
                history.getPositionX(),
                history.getPositionY(),
                history.getHeading(),
                history.getSpeed(),
                history.getMessageAt(),
                history.getReceivedAt(),
                history.getCreatedAt());
    }
}
