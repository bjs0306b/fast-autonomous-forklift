package com.fast.backend.forklift.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 구형 MQTT 송신자 호환 검증.
 *
 * <p>ROS2·Isaac Sim 송신자는 이번 변경 범위 밖이라 기존처럼 {@code battery}를 계속 보낸다. 백엔드가
 * 그 필드를 더 이상 쓰지 않더라도 <b>메시지 전체가 폐기되면 안 된다</b> — 상태가 화면에서 사라지기 때문이다.
 * 이 테스트가 그 계약을 고정한다.
 */
class ForkliftStatusMessageTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    /** 구형 payload — 백엔드가 모르는 battery 가 들어 있어도 나머지 필드가 정상 파싱돼야 한다. */
    @Test
    void legacyPayloadWithBattery_isParsedAndBatteryIgnored() throws Exception {
        String legacy = """
                {"forkliftId":"REAL-F01","status":"IDLE","battery":100,
                 "timestamp":"2026-08-03T14:00:00.000+09:00"}
                """;

        ForkliftStatusMessage message = objectMapper.readValue(legacy, ForkliftStatusMessage.class);

        assertThat(message.forkliftId()).isEqualTo("REAL-F01");
        assertThat(message.status()).isEqualTo("IDLE");
        assertThat(message.timestamp())
                .isEqualTo(OffsetDateTime.of(2026, 8, 3, 14, 0, 0, 0, ZoneOffset.ofHours(9)));
    }

    /** battery 외의 미지 필드(예: 송신자가 나중에 추가할 forkHeight)도 같은 이유로 무시돼야 한다. */
    @Test
    void unknownFieldsOtherThanBattery_areAlsoIgnored() {
        String payload = """
                {"forkliftId":"SIM-F01","status":"MOVING","battery":87,"forkHeight":1.2,"hasCargo":true,
                 "timestamp":"2026-08-03T14:00:00.000+09:00"}
                """;

        assertThatCode(() -> objectMapper.readValue(payload, ForkliftStatusMessage.class))
                .doesNotThrowAnyException();
    }

    /** 신형 payload — battery 가 없어도 정상 파싱된다. */
    @Test
    void payloadWithoutBattery_isParsed() throws Exception {
        String payload = """
                {"forkliftId":"REAL-F01","status":"MOVING",
                 "timestamp":"2026-08-03T14:00:00.000+09:00"}
                """;

        ForkliftStatusMessage message = objectMapper.readValue(payload, ForkliftStatusMessage.class);

        assertThat(message.forkliftId()).isEqualTo("REAL-F01");
        assertThat(message.status()).isEqualTo("MOVING");
    }
}
