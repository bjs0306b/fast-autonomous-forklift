package com.fast.backend.embedded.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedStoppedActionTest {

    @Test
    void fromRaw_knownAction_returnsMatchingEnum() {
        assertThat(EmbeddedStoppedAction.fromRaw("DRIVE")).contains(EmbeddedStoppedAction.DRIVE);
        assertThat(EmbeddedStoppedAction.fromRaw("steering")).contains(EmbeddedStoppedAction.STEERING);
        assertThat(EmbeddedStoppedAction.fromRaw("FORK")).contains(EmbeddedStoppedAction.FORK);
    }

    @Test
    void fromRaw_unknownOrBlankOrNull_returnsEmpty() {
        assertThat(EmbeddedStoppedAction.fromRaw("BRAKE")).isEmpty();
        assertThat(EmbeddedStoppedAction.fromRaw("")).isEmpty();
        assertThat(EmbeddedStoppedAction.fromRaw(null)).isEmpty();
    }
}
