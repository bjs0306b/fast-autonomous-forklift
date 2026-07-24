package com.fast.backend.common.time;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정 시간 규격(prompt32.md 1장 6번: Asia/Seoul, +09:00, ISO-8601)과 DB 저장·복원 방식을 고정한다.
 */
class CommunicationTimeTest {

    @Test
    void offset_isFixedToSeoul() {
        assertThat(CommunicationTime.OFFSET).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(CommunicationTime.nowOffset().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void toLocal_thenToOffset_roundTripsWithoutLoss() {
        OffsetDateTime original = OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9));

        LocalDateTime stored = CommunicationTime.toLocal(original);
        OffsetDateTime restored = CommunicationTime.toOffset(stored);

        assertThat(stored).isEqualTo(LocalDateTime.of(2026, 7, 23, 11, 20, 27));
        assertThat(restored).isEqualTo(original);
        assertThat(restored.getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void toLocal_convertsOtherOffsetsToTheSameInstantInSeoul() {
        // 문자열을 그대로 잘라내지 않고 같은 순간(instant)으로 정확히 환산해야 한다.
        OffsetDateTime utc = OffsetDateTime.of(2026, 7, 23, 2, 20, 27, 0, ZoneOffset.UTC);

        LocalDateTime stored = CommunicationTime.toLocal(utc);

        assertThat(stored).isEqualTo(LocalDateTime.of(2026, 7, 23, 11, 20, 27));
        assertThat(CommunicationTime.toOffset(stored).toInstant()).isEqualTo(utc.toInstant());
    }

    @Test
    void nullsArePassedThrough() {
        assertThat(CommunicationTime.toLocal(null)).isNull();
        assertThat(CommunicationTime.toOffset(null)).isNull();
    }

    @Test
    void serialization_producesIso8601WithSeoulOffset() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        OffsetDateTime value = OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9));

        assertThat(mapper.writeValueAsString(value)).isEqualTo("\"2026-07-23T11:20:27+09:00\"");
    }

    @Test
    void lenientModule_acceptsTimestampWithoutOffsetAndAssumesSeoul() throws Exception {
        // prompt32.md 3장 5번 하위 호환: 아직 오프셋을 붙이지 않는 발행 측 payload를 버리지 않는다.
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new CommunicationTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

        OffsetDateTime parsed = mapper.readValue("\"2026-07-23T11:20:27\"", OffsetDateTime.class);

        assertThat(parsed).isEqualTo(OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    void lenientModule_stillPreservesAnExplicitOffset() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new CommunicationTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

        OffsetDateTime parsed = mapper.readValue("\"2026-07-23T02:20:27Z\"", OffsetDateTime.class);

        assertThat(parsed.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(parsed.toInstant())
                .isEqualTo(OffsetDateTime.of(2026, 7, 23, 11, 20, 27, 0, ZoneOffset.ofHours(9)).toInstant());
    }

    @Test
    void lenientModule_stillRejectsGarbage() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new CommunicationTimeModule());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mapper.readValue("\"not-a-timestamp\"", OffsetDateTime.class))
                .isInstanceOf(Exception.class);
    }
}
