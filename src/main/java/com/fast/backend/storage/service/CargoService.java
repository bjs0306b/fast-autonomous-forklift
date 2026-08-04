package com.fast.backend.storage.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.common.time.CommunicationTime;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.dto.CargoResponse;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.transport.dto.TransportTaskResponse;
import com.fast.backend.transport.service.TransportTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 백엔드 화물 식별자와 대기 운반 작업을 원자적으로 생성하고 화물을 조회한다. */
@Service
public class CargoService {

    private static final Logger log = LoggerFactory.getLogger(CargoService.class);

    private final CargoMapper cargoMapper;
    private final TransportTaskService transportTaskService;

    public CargoService(CargoMapper cargoMapper, TransportTaskService transportTaskService) {
        this.cargoMapper = cargoMapper;
        this.transportTaskService = transportTaskService;
    }

    @Transactional
    public CargoResponse register() {
        Cargo cargo = new Cargo();
        LocalDateTime now = CommunicationTime.nowLocal();
        cargo.setCreatedAt(now);
        cargoMapper.insert(cargo);
        TransportTaskResponse task = transportTaskService.createTaskForCargo(cargo.getCargoId());
        log.info("Cargo and transport task registered: cargoId={}, taskId={}",
                cargo.getCargoId(), task.taskId());
        return CargoResponse.from(cargo, task);
    }

    @Transactional(readOnly = true)
    public Cargo getByCargoId(Long cargoId) {
        return cargoMapper.findByCargoId(cargoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARGO_NOT_FOUND,
                        "등록되지 않은 화물입니다: " + cargoId));
    }
}
