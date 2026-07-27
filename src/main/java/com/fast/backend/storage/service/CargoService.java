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

/**
 * 화물 등록·조회(prompt47.md 7장). 크기 검증·volume 계산은 도메인 {@link Cargo#create}가 담당하고,
 * 이 Service는 중복 검사·시각 세팅·저장 책임만 갖는다(기존 VehicleService와 동일한 역할 분담).
 */
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
        Cargo cargo = Cargo.create(request.cargoId(), request.width(), request.length(), request.height());
        LocalDateTime now = LocalDateTime.now();
        cargo.setCreatedAt(now);
        cargo.setUpdatedAt(now);
        cargoMapper.insert(cargo);
        log.info("Cargo registered: cargoId={}, volume={}", cargo.getCargoId(), cargo.getVolume());
        return CargoResponse.from(cargo);
    }

    @Transactional(readOnly = true)
    public Cargo getByCargoId(String cargoId) {
        return cargoMapper.findByCargoId(cargoId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CARGO_NOT_FOUND,
                        "등록되지 않은 화물입니다: " + cargoId));
    }
}
