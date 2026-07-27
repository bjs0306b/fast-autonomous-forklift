package com.fast.backend.storage.service;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;
import com.fast.backend.storage.dto.PalletCreateRequest;
import com.fast.backend.storage.dto.PalletResponse;
import com.fast.backend.storage.mapper.CargoMapper;
import com.fast.backend.storage.mapper.PalletMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 팔레트 등록·조회(prompt47.md 7장). 등록 시 연결 화물 존재를 확인하고, 초기 상태는 WAITING으로 둔다
 * (운반 대기 상태 — 배정 시 ASSIGNED로 전이). pickup heading은 degree [0,360) 범위만 허용한다.
 */
@Service
public class PalletService {

    private static final Logger log = LoggerFactory.getLogger(PalletService.class);

    private final PalletMapper palletMapper;
    private final CargoMapper cargoMapper;

    public PalletService(PalletMapper palletMapper, CargoMapper cargoMapper) {
        this.palletMapper = palletMapper;
        this.cargoMapper = cargoMapper;
    }

    @Transactional
    public PalletResponse register(PalletCreateRequest request) {
        if (palletMapper.existsByPalletId(request.palletId())) {
            throw new BusinessException(ErrorCode.PALLET_ID_DUPLICATED,
                    "이미 등록된 palletId입니다: " + request.palletId());
        }
        if (!cargoMapper.existsByCargoId(request.cargoId())) {
            throw new BusinessException(ErrorCode.CARGO_NOT_FOUND,
                    "등록되지 않은 화물입니다: " + request.cargoId());
        }

        Pallet pallet = new Pallet();
        pallet.setPalletId(request.palletId());
        pallet.setCargoId(request.cargoId());
        if (request.pickup() != null) {
            pallet.setPickupX(request.pickup().x());
            pallet.setPickupY(request.pickup().y());
            pallet.setPickupHeading(validateHeading(request.pickup().heading()));
        }
        pallet.setStatus(PalletStatus.WAITING);
        LocalDateTime now = LocalDateTime.now();
        pallet.setCreatedAt(now);
        pallet.setUpdatedAt(now);
        palletMapper.insert(pallet);
        log.info("Pallet registered: palletId={}, cargoId={}", pallet.getPalletId(), pallet.getCargoId());
        return PalletResponse.from(pallet);
    }

    /** heading은 선택값이지만, 주어지면 degree [0,360) 범위여야 한다(기존 위치 규격과 동일). */
    private Double validateHeading(Double heading) {
        if (heading == null) {
            return null;
        }
        if (heading < 0.0 || heading >= 360.0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "pickup.heading은 [0,360) 범위여야 합니다: " + heading);
        }
        return heading;
    }
}
