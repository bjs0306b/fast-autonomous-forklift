package com.fast.backend.storage.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.dto.CargoCreateRequest;
import com.fast.backend.storage.dto.CargoResponse;
import com.fast.backend.storage.mapper.CargoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 화물 식별자를 등록하고 조회한다. 치수는 측정 결과에만 저장한다. */
@Service
public class CargoService {

    private static final Logger log = LoggerFactory.getLogger(CargoService.class);

    private final CargoMapper cargoMapper;

    public CargoService(CargoMapper cargoMapper) {
        this.cargoMapper = cargoMapper;
    }

    @Transactional
    public CargoResponse register(CargoCreateRequest request) {
        if (cargoMapper.existsByCargoId(request.cargoId())) {
            throw new BusinessException(ErrorCode.CARGO_ID_DUPLICATED,
                    "이미 등록된 cargoId입니다: " + request.cargoId());
        }
        Cargo cargo = new Cargo();
        cargo.setCargoId(request.cargoId());
        LocalDateTime now = LocalDateTime.now();
        cargo.setCreatedAt(now);
        cargoMapper.insert(cargo);
        log.info("Cargo registered: cargoId={}", cargo.getCargoId());
        return CargoResponse.from(cargo);
    }

    @Transactional(readOnly = true)
    public Cargo getByCargoId(String cargoId) {
        return cargoMapper.findByCargoId(cargoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARGO_NOT_FOUND,
                        "등록되지 않은 화물입니다: " + cargoId));
    }
}
