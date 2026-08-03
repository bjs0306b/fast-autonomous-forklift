package com.fast.backend.transport.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.transport.domain.TransportTask;
import com.fast.backend.transport.mapper.TransportTaskMapper;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 가장 오래 대기한 작업과 사용 가능한 차량을 한 건씩 매칭한다. */
@Service
public class TransportSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(TransportSchedulingService.class);

    private final TransportTaskMapper taskMapper;
    private final VehicleCurrentStatusMapper vehicleStatusMapper;
    private final TransportTaskService taskService;

    public TransportSchedulingService(
            TransportTaskMapper taskMapper,
            VehicleCurrentStatusMapper vehicleStatusMapper,
            TransportTaskService taskService) {
        this.taskMapper = taskMapper;
        this.vehicleStatusMapper = vehicleStatusMapper;
        this.taskService = taskService;
    }

    /**
     * 1회 실행마다 가장 오래된 PENDING 작업 하나만 배정한다.
     * 조회 이후 상태가 바뀌는 경합은 {@link TransportTaskService#assign(String, String)}의
     * 조건부 갱신이 최종적으로 방어한다.
     */
    public void matchNext() {
        TransportTask task = taskMapper.findOldestPending().orElse(null);
        if (task == null) {
            return;
        }

        String vehicleId = vehicleStatusMapper.findFirstAvailableIdleVehicleId().orElse(null);
        if (vehicleId == null) {
            return;
        }

        try {
            taskService.assign(task.getTaskCode(), vehicleId);
            log.info("Transport task matched automatically: taskId={}, vehicleId={}",
                    task.getTaskCode(), vehicleId);
        } catch (BusinessException exception) {
            // 다음 주기에 최신 상태로 다시 조회한다. 다른 요청이 먼저 배정한 정상 경합도 여기에 포함된다.
            log.debug("Automatic transport matching skipped after concurrent state change: "
                            + "taskId={}, vehicleId={}, errorCode={}",
                    task.getTaskCode(), vehicleId, exception.getErrorCode());
        }
    }
}

