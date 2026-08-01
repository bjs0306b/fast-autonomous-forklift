package com.fast.backend.forklift.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.forklift.dto.ForkliftStatusMessage;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 지게차 상태 메시지를 처리한다. {@code forkliftId}는 곧 차량 도메인의 {@code vehicleId}이므로
 * (예: {@code REAL-F01}, {@code SIM-F01}), 이 메시지를 {@link VehicleStatusUpdateCommand}로 변환해
 * {@link VehicleStatusService#updateCurrentStatus}에 위임한다(prompt20.md 11장) — DB upsert와
 * WebSocket 브로드캐스트는 전부 그 메서드가 책임진다. 이 클래스는 "MQTT 상태 메시지를 어떻게 Command로
 * 바꾸고, Service가 던지는 예외를 어떻게 다운그레이드할지"만 안다(MQTT Receiver/Router가 DB 로직을
 * 직접 처리하지 않도록 하는 경계, prompt20.md 18장).
 *
 * <p>{@link ForkliftStatusMessage}는 위치·속도·방향 필드가 없다 — 그래서 여기서 만드는 Command는
 * positionX/positionY/heading/speed를 항상 null로 둔다. 위치는 별도 토픽(location)이자 별도 처리
 * 경로({@link ForkliftLocationService})이며, 같은 vehicle_current_status upsert를 공유하지 않는다
 * (이유는 {@link com.fast.backend.vehicle.websocket.VehicleLocationEventData} Javadoc 참고).
 *
 * <p>다음 경우는 경고 로그만 남기고 예외를 밖으로 던지지 않는다(애플리케이션 계속 동작,
 * prompt20.md 11장): 미등록 차량({@code VEHICLE_NOT_FOUND}), 배터리 범위 오류
 * ({@code VEHICLE_BATTERY_OUT_OF_RANGE}), 그 외 예상하지 못한 런타임 예외(DB 오류 등).
 */
@Service
public class ForkliftStatusService {

    private static final Logger log = LoggerFactory.getLogger(ForkliftStatusService.class);

    private final VehicleStatusService vehicleStatusService;

    public ForkliftStatusService(VehicleStatusService vehicleStatusService) {
        this.vehicleStatusService = vehicleStatusService;
    }

    public void handleStatus(ForkliftStatusMessage message) {
        log.info("Forklift status updated: forkliftId={}, status={}, battery={}, timestamp={}",
                message.forkliftId(), message.status(), message.battery(), message.timestamp());

        VehicleStatusUpdateCommand command = new VehicleStatusUpdateCommand(
                message.status(), message.battery(), message.timestamp());

        try {
            vehicleStatusService.updateCurrentStatus(message.forkliftId(), command);
        } catch (BusinessException e) {
            log.warn("Vehicle status update skipped: vehicleId={}, errorCode={}, message={}",
                    message.forkliftId(), e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Vehicle status update failed unexpectedly: vehicleId={}, error={}",
                    message.forkliftId(), e.getMessage());
        }
    }
}
