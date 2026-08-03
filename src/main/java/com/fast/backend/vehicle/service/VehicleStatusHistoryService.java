package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryListResponse;
import com.fast.backend.vehicle.dto.VehicleStatusHistoryResponse;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 차량 상태 이력 조회(Jira -133). 이력 <b>저장</b>은 최신 상태 저장과 같은 트랜잭션이어야 하므로
 * {@link VehicleStatusService#updateCurrentStatus}가 담당하고, 이 서비스는 조회만 책임진다.
 *
 * <p>상태 이력이 없는 차량도 정상 응답(빈 목록)이다 — "등록된 차량인데 아직 이력이 없음"과 "등록되지 않은
 * 차량"을 구분하기 위해 차량 존재 여부만 먼저 확인한다.
 */
@Service
public class VehicleStatusHistoryService {

    /** 한 번에 가져갈 수 있는 최대 건수. 기존 명령 조회(limit 1~200)보다 좁게 잡은 API 계약값이다. */
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;

    private final VehicleMapper vehicleMapper;
    private final VehicleStatusHistoryMapper historyMapper;

    public VehicleStatusHistoryService(
            VehicleMapper vehicleMapper, VehicleStatusHistoryMapper historyMapper) {
        this.vehicleMapper = vehicleMapper;
        this.historyMapper = historyMapper;
    }

    /**
     * @param from 조회 시작 시각(포함). null이면 제한 없음
     * @param to   조회 종료 시각(제외). null이면 제한 없음
     * @param rawStatus 특정 상태만 조회. null·빈 문자열이면 전체
     */
    @Transactional(readOnly = true)
    public VehicleStatusHistoryListResponse getHistory(
            String vehicleId, OffsetDateTime from, OffsetDateTime to,
            String rawStatus, int page, int size) {
        if (!vehicleMapper.existsByVehicleId(vehicleId)) {
            throw new BusinessException(ErrorCode.VEHICLE_NOT_FOUND, "등록되지 않은 차량입니다: " + vehicleId);
        }
        validatePaging(page, size);
        validateRange(from, to);

        VehicleStatus status = parseStatus(rawStatus);
        LocalDateTime fromLocal = CommunicationTime.toLocal(from);
        LocalDateTime toLocal = CommunicationTime.toLocal(to);

        long totalElements = historyMapper.countByVehicleId(vehicleId, fromLocal, toLocal, status);
        List<VehicleStatusHistoryResponse> items = historyMapper
                .findByVehicleId(vehicleId, fromLocal, toLocal, status, size, page * size)
                .stream().map(VehicleStatusHistoryResponse::from).toList();
        return VehicleStatusHistoryListResponse.of(vehicleId, page, size, totalElements, items);
    }

    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "page는 0 이상이어야 합니다: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "size는 1~" + MAX_SIZE + " 범위여야 합니다: " + size);
        }
    }

    /** 기간은 {@code from <= message_at < to}이므로 {@code from >= to}는 결과가 항상 비는 잘못된 요청이다. */
    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from은 to보다 이전이어야 합니다: from=" + from + ", to=" + to);
        }
    }

    /**
     * {@link VehicleStatus#fromRaw}를 쓰지 않는다 — 그 메서드는 알 수 없는 값을 {@code UNKNOWN}으로
     * 흡수하는데, 조회 필터에서 그러면 오타가 "UNKNOWN 상태 조회"로 조용히 바뀐다. 기존
     * {@code TransportTaskController#parseStatus}와 같은 방식으로 거부한다.
     */
    private VehicleStatus parseStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return null;
        }
        try {
            return VehicleStatus.valueOf(rawStatus.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 status입니다: " + rawStatus);
        }
    }
}
