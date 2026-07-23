package com.fast.backend.ai.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoadBalanceDirectionTest {

    @Test
    void fromRaw_knownValues_mapCorrectly() {
        assertThat(LoadBalanceDirection.fromRaw("left")).contains(LoadBalanceDirection.LEFT);
        assertThat(LoadBalanceDirection.fromRaw("right")).contains(LoadBalanceDirection.RIGHT);
        assertThat(LoadBalanceDirection.fromRaw("front")).contains(LoadBalanceDirection.FRONT);
        assertThat(LoadBalanceDirection.fromRaw("back")).contains(LoadBalanceDirection.BACK);
    }

    @Test
    void fromRaw_unknownValue_returnsEmpty() {
        assertThat(LoadBalanceDirection.fromRaw("up")).isEmpty();
    }

    @Test
    void fromRaw_nullOrBlank_returnsEmpty() {
        assertThat(LoadBalanceDirection.fromRaw(null)).isEmpty();
        assertThat(LoadBalanceDirection.fromRaw(" ")).isEmpty();
    }

    @Test
    void opposite_returnsCorrectPairs() {
        assertThat(LoadBalanceDirection.LEFT.opposite()).isEqualTo(LoadBalanceDirection.RIGHT);
        assertThat(LoadBalanceDirection.RIGHT.opposite()).isEqualTo(LoadBalanceDirection.LEFT);
        assertThat(LoadBalanceDirection.FRONT.opposite()).isEqualTo(LoadBalanceDirection.BACK);
        assertThat(LoadBalanceDirection.BACK.opposite()).isEqualTo(LoadBalanceDirection.FRONT);
    }
}
