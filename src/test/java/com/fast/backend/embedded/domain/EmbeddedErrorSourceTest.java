package com.fast.backend.embedded.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedErrorSourceTest {

    @Test
    void fromRaw_knownSource_returnsMatchingEnum() {
        assertThat(EmbeddedErrorSource.fromRaw("DRIVE")).contains(EmbeddedErrorSource.DRIVE);
        assertThat(EmbeddedErrorSource.fromRaw("steering")).contains(EmbeddedErrorSource.STEERING);
        assertThat(EmbeddedErrorSource.fromRaw("FORK")).contains(EmbeddedErrorSource.FORK);
        assertThat(EmbeddedErrorSource.fromRaw("LIMIT_SWITCH")).contains(EmbeddedErrorSource.LIMIT_SWITCH);
        assertThat(EmbeddedErrorSource.fromRaw("UART")).contains(EmbeddedErrorSource.UART);
        assertThat(EmbeddedErrorSource.fromRaw("SYSTEM")).contains(EmbeddedErrorSource.SYSTEM);
    }

    @Test
    void fromRaw_unknownOrBlankOrNull_returnsEmpty() {
        assertThat(EmbeddedErrorSource.fromRaw("NETWORK")).isEmpty();
        assertThat(EmbeddedErrorSource.fromRaw("")).isEmpty();
        assertThat(EmbeddedErrorSource.fromRaw(null)).isEmpty();
    }
}
