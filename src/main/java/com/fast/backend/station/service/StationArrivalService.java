package com.fast.backend.station.service;

import com.fast.backend.station.dto.VehicleArrivedMessage;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 입고 바이 도착 MQTT를 측정 대기 전이로 연결한다. */
@Service
public class StationArrivalService {

    private static final Logger log = LoggerFactory.getLogger(StationArrivalService.class);

    private final VehicleIdAliasResolver aliasResolver;
    private final StationMeasurementRequestWorkflow workflow;

    public StationArrivalService(
            VehicleIdAliasResolver aliasResolver,
            StationMeasurementRequestWorkflow workflow) {
        this.aliasResolver = aliasResolver;
        this.workflow = workflow;
    }

    public void handleArrival(String topicVehicleId, VehicleArrivedMessage message) {
        if (topicVehicleId == null || topicVehicleId.isBlank()) {
            return;
        }
        if (message != null && message.vehicleId() != null && !message.vehicleId().isBlank()
                && !topicVehicleId.equalsIgnoreCase(message.vehicleId().trim())) {
            log.warn("Arrival vehicle ID mismatch; topic ID is used: topicVehicleId={}, payloadVehicleId={}",
                    topicVehicleId, message.vehicleId());
        }
        String vehicleId = aliasResolver.resolve(topicVehicleId).orElse(null);
        if (vehicleId == null || vehicleId.isBlank()) {
            return;
        }
        workflow.handleArrival(vehicleId, message == null ? null : message.taskId());
    }
}
