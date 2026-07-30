package com.fast.backend.vehicle.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleCreateRequest;
import com.fast.backend.vehicle.dto.VehicleDetailResponse;
import com.fast.backend.vehicle.dto.VehicleResponse;
import com.fast.backend.vehicle.dto.VehicleStatusCountResponse;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusCountRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 차량 기본 정보(등록/조회/집계)를 담당한다. 최신 상태 "갱신"은 {@link VehicleStatusService}가 맡고,
 * 이 클래스는 조회 시 두 테이블(vehicle, vehicle_current_status)을 조합하는 읽기 전용 책임까지 갖는다
 * (목록/상세/집계 API가 결국 두 테이블을 함께 봐야 하므로, 서비스 간 상호 호출로 순환 의존을 만들기보다
 * 읽기 조합을 한 곳에 모았다 — answer15.md 9장에서 이 구조를 설명한다).
 */
@Service
public class VehicleService {

    private static final Logger log = LoggerFactory.getLogger(VehicleService.class);

    private final VehicleMapper vehicleMapper;
    private final VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    public VehicleService(VehicleMapper vehicleMapper, VehicleCurrentStatusMapper vehicleCurrentStatusMapper) {
        this.vehicleMapper = vehicleMapper;
        this.vehicleCurrentStatusMapper = vehicleCurrentStatusMapper;
    }

    @Transactional
    public VehicleDetailResponse register(VehicleCreateRequest request) {
        if (vehicleMapper.existsByVehicleId(request.vehicleId())) {
            throw new BusinessException(ErrorCode.VEHICLE_ID_DUPLICATED,
                    "이미 등록된 vehicleId입니다: " + request.vehicleId());
        }

        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(request.vehicleId());
        vehicle.setName(request.name());
        vehicle.setSource(request.source());
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicle.setUpdatedAt(now);
        vehicleMapper.insert(vehicle);

        VehicleCurrentStatus initialStatus = new VehicleCurrentStatus();
        initialStatus.setVehicleId(vehicle.getVehicleId());
        initialStatus.setStatus(VehicleStatus.UNKNOWN);
        initialStatus.setReceivedAt(now);
        vehicleCurrentStatusMapper.upsert(initialStatus);

        log.info("Vehicle registered: vehicleId={}, source={}", vehicle.getVehicleId(), vehicle.getSource());
        return toDetailResponse(vehicle, toStatusResponse(initialStatus));
    }

    @Transactional(readOnly = true)
    public List<VehicleResponse> findActiveVehicles() {
        List<Vehicle> vehicles = vehicleMapper.findAllActive();
        if (vehicles.isEmpty()) {
            return List.of();
        }

        Map<String, VehicleCurrentStatus> statusByVehicleId = loadStatusMap(
                vehicles.stream().map(Vehicle::getVehicleId).collect(Collectors.toList()));

        return vehicles.stream()
                .map(vehicle -> toResponse(vehicle, statusByVehicleId.get(vehicle.getVehicleId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public VehicleDetailResponse getDetail(String vehicleId) {
        Vehicle vehicle = vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));
        VehicleCurrentStatus status = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        return toDetailResponse(vehicle, toStatusResponse(status));
    }

    @Transactional
    public VehicleDetailResponse updateActive(String vehicleId, boolean active) {
        Vehicle vehicle = vehicleMapper.findByVehicleId(vehicleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VEHICLE_NOT_FOUND,
                        "등록되지 않은 차량입니다: " + vehicleId));

        LocalDateTime now = LocalDateTime.now();
        vehicleMapper.updateActive(vehicleId, active, now);
        vehicle.setActive(active);
        vehicle.setUpdatedAt(now);

        VehicleCurrentStatus status = vehicleCurrentStatusMapper.findByVehicleId(vehicleId).orElse(null);
        log.info("Vehicle active state updated: vehicleId={}, active={}", vehicleId, active);
        return toDetailResponse(vehicle, toStatusResponse(status));
    }

    @Transactional(readOnly = true)
    public VehicleStatusCountResponse countByStatus() {
        List<VehicleStatusCountRow> rows = vehicleCurrentStatusMapper.countByStatusForActiveVehicles();

        // 상태값이 한 번도 관측되지 않은 항목도 0으로 채워, 응답에 항상 확정 enum 10종이 전부 나오게 한다
        // (prompt32.md 1장 3번 "상태별 0건 기본값 처리". 등록된 차량이 아예 없을 때도 total=0 + 10개 항목).
        // VehicleStatus.values()를 그대로 순회하므로 enum에 값을 추가하면 이 응답도 자동으로 따라간다.
        Map<String, Long> countByStatus = new LinkedHashMap<>();
        for (VehicleStatus status : VehicleStatus.values()) {
            countByStatus.put(status.name(), 0L);
        }
        long total = 0;
        for (VehicleStatusCountRow row : rows) {
            VehicleStatus status = VehicleStatus.fromRaw(row.getStatus());
            countByStatus.merge(status.name(), row.getCount(), Long::sum);
            total += row.getCount();
        }

        List<VehicleStatusCountResponse.StatusCount> items = countByStatus.entrySet().stream()
                .map(entry -> new VehicleStatusCountResponse.StatusCount(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

        return new VehicleStatusCountResponse(total, items);
    }

    private Map<String, VehicleCurrentStatus> loadStatusMap(List<String> vehicleIds) {
        return vehicleCurrentStatusMapper.findAllByVehicleIds(vehicleIds).stream()
                .collect(Collectors.toMap(VehicleCurrentStatus::getVehicleId, status -> status));
    }

    private VehicleResponse toResponse(Vehicle vehicle, VehicleCurrentStatus status) {
        return new VehicleResponse(
                vehicle.getVehicleId(),
                vehicle.getName(),
                vehicle.getSource(),
                vehicle.isActive(),
                toStatusResponse(status));
    }

    private VehicleDetailResponse toDetailResponse(Vehicle vehicle, VehicleStatusResponse statusResponse) {
        return new VehicleDetailResponse(
                vehicle.getVehicleId(),
                vehicle.getName(),
                vehicle.getSource(),
                vehicle.isActive(),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt(),
                statusResponse);
    }

    /**
     * 상태 행이 없으면(한 번도 상태를 수신하지 않은 차량) null 대신 status=UNKNOWN을 가진 응답 객체를
     * 반환한다 — React가 "상태 없음"과 "정상 UNKNOWN 상태"를 항상 같은 모양으로 다룰 수 있도록 하기
     * 위한 설계 결정이다(prompt16.md 13장 "UNKNOWN 또는 null 중 분석 후 일관 적용", answer15.md 13장).
     */
    private VehicleStatusResponse toStatusResponse(VehicleCurrentStatus status) {
        if (status == null) {
            return VehicleStatusResponse.unknown();
        }
        return new VehicleStatusResponse(
                status.getStatus(),
                status.getBattery(),
                status.getPositionX(),
                status.getPositionY(),
                status.getHeading(),
                status.getSpeed(),
                status.getForkHeight(),
                status.getHasCargo(),
                status.getCargoId(),
                status.getFootprintLength(),
                status.getFootprintWidth(),
                CommunicationTime.toOffset(status.getMessageAt()),
                CommunicationTime.toOffset(status.getReceivedAt()));
    }
}
