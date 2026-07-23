package com.fast.backend.embedded.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** prompt29.md 21장 "알려지지 않은 command 거부" — UNKNOWN 흡수 없이 Optional.empty()로 거부한다. */
class EmbeddedCommandTypeTest {

    @Test
    void fromRaw_knownCommand_returnsMatchingEnum() {
        assertThat(EmbeddedCommandType.fromRaw("STOP")).contains(EmbeddedCommandType.STOP);
        assertThat(EmbeddedCommandType.fromRaw("fork_up")).contains(EmbeddedCommandType.FORK_UP);
        assertThat(EmbeddedCommandType.fromRaw("FORK_DOWN")).contains(EmbeddedCommandType.FORK_DOWN);
        assertThat(EmbeddedCommandType.fromRaw("LOAD")).contains(EmbeddedCommandType.LOAD);
        assertThat(EmbeddedCommandType.fromRaw("UNLOAD")).contains(EmbeddedCommandType.UNLOAD);
        assertThat(EmbeddedCommandType.fromRaw("EMERGENCY_STOP")).contains(EmbeddedCommandType.EMERGENCY_STOP);
        assertThat(EmbeddedCommandType.fromRaw("RESET_ESTOP")).contains(EmbeddedCommandType.RESET_ESTOP);
    }

    @Test
    void fromRaw_unknownOrBlankOrNull_returnsEmpty() {
        assertThat(EmbeddedCommandType.fromRaw("MOVE")).isEmpty();
        assertThat(EmbeddedCommandType.fromRaw("")).isEmpty();
        assertThat(EmbeddedCommandType.fromRaw(" ")).isEmpty();
        assertThat(EmbeddedCommandType.fromRaw(null)).isEmpty();
    }
}
