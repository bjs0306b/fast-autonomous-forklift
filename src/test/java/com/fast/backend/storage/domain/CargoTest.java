package com.fast.backend.storage.domain;

import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 화물 크기 검증·volume 계산(prompt46.md 4장).
 */
class CargoTest {

    @Test
    void create_computesVolumeFromDimensions() {
        Cargo cargo = Cargo.create("CARGO-001", 0.8, 1.0, 0.6);
        assertThat(cargo.getVolume()).isEqualTo(0.8 * 1.0 * 0.6);
    }

    @Test
    void create_zeroOrNegativeDimension_isRejected() {
        assertThatThrownBy(() -> Cargo.create("CARGO-001", 0.0, 1.0, 0.6))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CARGO_DIMENSION_INVALID);
        assertThatThrownBy(() -> Cargo.create("CARGO-001", 0.8, -1.0, 0.6))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_blankCargoId_isRejected() {
        assertThatThrownBy(() -> Cargo.create("  ", 0.8, 1.0, 0.6))
                .isInstanceOf(BusinessException.class);
    }
}
