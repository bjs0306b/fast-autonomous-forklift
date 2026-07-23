package com.fast.backend.vehicle.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateRequest;
import com.fast.backend.vehicle.service.VehicleStatusService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;

/**
 * 실제 MQTT(ROS2·Isaac Sim) 연동 전까지 상태 갱신 로직을 검증하기 위한 임시 API
 * (prompt16.md 12장). {@code MqttTestController}와 동일한 패턴 — 독립 프로퍼티 스위치로 켜고 끈다
 * ({@code @Profile}이 아니라 {@code mqtt.test-api.enabled}와 같은 방식을 그대로 재사용, 이유는
 * answer13.md/`MqttTestController` 참고: 아직 prod 프로필이 없어 프로필만으로는 운영 차단 효과가 없음).
 *
 * <p>기본값은 비활성화({@code false})이며, {@code application-local.yml}에서만 명시적으로 켠다.
 *
 * <p>내부적으로는 {@link VehicleStatusService#updateCurrentStatus}를 그대로 호출한다 — 실제 MQTT
 * 연동이 완료된 뒤에도 이 Service 메서드는 그대로 재사용된다(18장 조건).
 */
@RestController
@ConditionalOnProperty(name = "vehicle.status-test-api.enabled", havingValue = "true", matchIfMissing = false)
public class VehicleStatusTestController {

    private final VehicleStatusService vehicleStatusService;

    public VehicleStatusTestController(VehicleStatusService vehicleStatusService) {
        this.vehicleStatusService = vehicleStatusService;
    }

    @PutMapping("/api/vehicles/{vehicleId}/status")
    public ApiResponse<VehicleStatusResponse> updateStatus(
            @PathVariable String vehicleId, @Valid @RequestBody VehicleStatusUpdateRequest request) {
        VehicleStatusUpdateCommand command = new VehicleStatusUpdateCommand(
                request.status(),
                request.battery(),
                request.positionX(),
                request.positionY(),
                request.heading(),
                request.speed(),
                request.messageAt() != null
                        ? request.messageAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                        : null);

        VehicleStatusResponse response = vehicleStatusService.updateCurrentStatus(vehicleId, command);
        return ApiResponse.success(response);
    }
}
