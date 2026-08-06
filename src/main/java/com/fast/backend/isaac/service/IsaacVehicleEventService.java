package com.fast.backend.isaac.service;

import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.isaac.dto.IsaacVehicleEventMessage;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.service.VehicleAutoRegistrar;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import com.fast.backend.vehicle.service.VehicleStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;

import static com.fast.backend.common.time.CommunicationTime.OFFSET;

/** Isaac event 토픽의 상태 전이·오류를 차량 상태 정본과 WebSocket으로 연결한다. */
@Service
public class IsaacVehicleEventService {

    private static final Logger log = LoggerFactory.getLogger(IsaacVehicleEventService.class);

    private final VehicleIdAliasResolver aliasResolver;
    private final VehicleAutoRegistrar autoRegistrar;
    private final VehicleStatusService statusService;

    public IsaacVehicleEventService(
            VehicleIdAliasResolver aliasResolver,
            VehicleAutoRegistrar autoRegistrar,
            VehicleStatusService statusService) {
        this.aliasResolver = aliasResolver;
        this.autoRegistrar = autoRegistrar;
        this.statusService = statusService;
    }

    public void handleEvent(String topicVehicleId, IsaacVehicleEventMessage message) {
        if (message == null) {
            return;
        }
        String rawVehicleId = hasText(message.vehicleId()) ? message.vehicleId().trim() : topicVehicleId;
        String vehicleId = aliasResolver.resolve(rawVehicleId).orElse(null);
        if (!hasText(vehicleId) || !autoRegistrar.ensureRegistered(vehicleId, "isaac-event")) {
            log.warn("Isaac event discarded: vehicleId={}", rawVehicleId);
            return;
        }

        String rawState = hasText(message.state()) ? message.state() : message.event();
        if ("ERROR".equalsIgnoreCase(message.event())) {
            rawState = "ERROR";
        }
        VehicleStatus status = IsaacVehicleStateMapper.fromRaw(rawState);
        if (status == VehicleStatus.UNKNOWN) {
            log.debug("Isaac event ignored because it has no known state: vehicleId={}, event={}, state={}",
                    vehicleId, message.event(), message.state());
            return;
        }
        statusService.updateCurrentStatus(vehicleId, new VehicleStatusUpdateCommand(
                status.name(), toMessageAt(message.ts()), null, null));
    }

    private OffsetDateTime toMessageAt(Long ts) {
        return ts == null || ts <= 0
                ? CommunicationTime.nowOffset()
                : Instant.ofEpochMilli(ts).atOffset(OFFSET);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
