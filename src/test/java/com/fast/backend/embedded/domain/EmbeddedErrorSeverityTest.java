package com.fast.backend.embedded.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddedErrorSeverityTest {

    @Test
    void fromRaw_knownSeverity_returnsMatchingEnum() {
        assertThat(EmbeddedErrorSeverity.fromRaw("WARNING")).contains(EmbeddedErrorSeverity.WARNING);
        assertThat(EmbeddedErrorSeverity.fromRaw("error")).contains(EmbeddedErrorSeverity.ERROR);
        assertThat(EmbeddedErrorSeverity.fromRaw("CRITICAL")).contains(EmbeddedErrorSeverity.CRITICAL);
    }

    @Test
    void fromRaw_unknownOrBlankOrNull_returnsEmpty() {
        assertThat(EmbeddedErrorSeverity.fromRaw("FATAL")).isEmpty();
        assertThat(EmbeddedErrorSeverity.fromRaw("")).isEmpty();
        assertThat(EmbeddedErrorSeverity.fromRaw(null)).isEmpty();
    }
}
