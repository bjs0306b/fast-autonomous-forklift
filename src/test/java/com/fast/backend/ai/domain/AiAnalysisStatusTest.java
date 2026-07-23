package com.fast.backend.ai.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisStatusTest {

    @Test
    void fromRaw_ok_returnsOk() {
        assertThat(AiAnalysisStatus.fromRaw("ok")).contains(AiAnalysisStatus.OK);
    }

    @Test
    void fromRaw_noDetection_returnsNoDetection() {
        assertThat(AiAnalysisStatus.fromRaw("no_detection")).contains(AiAnalysisStatus.NO_DETECTION);
    }

    @Test
    void fromRaw_unreliable_returnsUnreliable() {
        assertThat(AiAnalysisStatus.fromRaw("unreliable")).contains(AiAnalysisStatus.UNRELIABLE);
    }

    @Test
    void fromRaw_uppercase_isCaseInsensitive() {
        assertThat(AiAnalysisStatus.fromRaw("OK")).contains(AiAnalysisStatus.OK);
    }

    @Test
    void fromRaw_unknownValue_returnsEmpty() {
        assertThat(AiAnalysisStatus.fromRaw("moving")).isEqualTo(Optional.empty());
    }

    @Test
    void fromRaw_nullOrBlank_returnsEmpty() {
        assertThat(AiAnalysisStatus.fromRaw(null)).isEmpty();
        assertThat(AiAnalysisStatus.fromRaw("  ")).isEmpty();
    }

    @Test
    void rawValue_serializesToLowercase() {
        assertThat(AiAnalysisStatus.OK.rawValue()).isEqualTo("ok");
        assertThat(AiAnalysisStatus.NO_DETECTION.rawValue()).isEqualTo("no_detection");
        assertThat(AiAnalysisStatus.UNRELIABLE.rawValue()).isEqualTo("unreliable");
    }
}
