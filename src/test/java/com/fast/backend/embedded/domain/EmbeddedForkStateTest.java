package com.fast.backend.embedded.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedForkStateTest {

    @Test
    void fromRaw_knownState_returnsMatchingEnum() {
        assertThat(EmbeddedForkState.fromRaw("MOVING_UP")).contains(EmbeddedForkState.MOVING_UP);
        assertThat(EmbeddedForkState.fromRaw("moving_down")).contains(EmbeddedForkState.MOVING_DOWN);
        assertThat(EmbeddedForkState.fromRaw("STOPPED")).contains(EmbeddedForkState.STOPPED);
        assertThat(EmbeddedForkState.fromRaw("BOTTOM")).contains(EmbeddedForkState.BOTTOM);
        assertThat(EmbeddedForkState.fromRaw("ERROR")).contains(EmbeddedForkState.ERROR);
        assertThat(EmbeddedForkState.fromRaw("UNKNOWN")).contains(EmbeddedForkState.UNKNOWN);
    }

    @Test
    void fromRaw_unknownOrBlankOrNull_returnsEmpty() {
        assertThat(EmbeddedForkState.fromRaw("LIFTING")).isEmpty();
        assertThat(EmbeddedForkState.fromRaw("")).isEmpty();
        assertThat(EmbeddedForkState.fromRaw(null)).isEmpty();
    }
}
