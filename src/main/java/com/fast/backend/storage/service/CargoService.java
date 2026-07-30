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
 * 화물 등록·조회.
 *
 * <p>FR-202 최종 스키마(prompt85)에서 치수 컬럼이 사라져 크기 검증·volume 계산이 함께 없어졌다 —
 * 등록은 식별자 중복 검사와 저장만 한다. 크기 정보는 측정 스테이션 경로로만 들어온다.
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
        Cargo cargo = new Cargo();
        cargo.setCargoId(request.cargoId());
        cargo.setCreatedAt(LocalDateTime.now());
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
