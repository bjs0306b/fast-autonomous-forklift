package com.fast.backend.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DimensionScaleTest {

    @Test
    void fromRaw_real_returnsReal() {
        assertThat(DimensionScale.fromRaw("REAL")).contains(DimensionScale.REAL);
    }

    @Test
    void fromRaw_lowercaseMiniature_returnsMiniature() {
        assertThat(DimensionScale.fromRaw("miniature")).contains(DimensionScale.MINIATURE);
    }

    @Test
    void fromRaw_unknownValue_returnsEmpty() {
        assertThat(DimensionScale.fromRaw("HALF")).isEmpty();
    }

    @Test
    void fromRaw_nullOrBlank_returnsEmpty() {
        assertThat(DimensionScale.fromRaw(null)).isEmpty();
        assertThat(DimensionScale.fromRaw("")).isEmpty();
    }
}
