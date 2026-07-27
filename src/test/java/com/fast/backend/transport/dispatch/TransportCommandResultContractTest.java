package com.fast.backend.transport.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.command.dto.VehicleCommandResultMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROS2 TRANSPORT 결과 계약(prompt49.md 9장) 호환성 검증 — <b>백엔드 범위 한정</b>.
 *
 * <p>prompt49는 ROS2 브리지(Python) 구현 과제이고 §18에서 백엔드 수정을 금지한다. 따라서 백엔드에서
 * 실제로 확인해야 하는 유일한 항목은 §9·§302의 "ROS2가 결과에 {@code stage}/{@code failureCode}/{@code taskId}를
 * 추가로 넣어도 기존 백엔드 결과 DTO({@link VehicleCommandResultMessage})가 이를 무시하고 파싱하는가"이다.
 *
 * <p>이 테스트는 <b>실제 Spring {@code ObjectMapper} 빈</b>(MqttMessageRouter가 command-result 파싱에
 * 쓰는 바로 그 매퍼)으로 검증한다 — Spring Boot 기본 설정이 FAIL_ON_UNKNOWN_PROPERTIES를 비활성화하므로
 * 미지 필드가 있어도 예외 없이 무시된다는 사실을 실행으로 증명한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransportCommandResultContractTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void successResult_parsesWithBackendDto() throws Exception {
        String json = """
                {"commandId":"TCMD-550e8400","vehicleId":"REAL-F01","result":"SUCCESS",
                 "message":"transport completed","completedAt":"2026-07-27T15:05:00+09:00"}
                """;

        VehicleCommandResultMessage msg = objectMapper.readValue(json, VehicleCommandResultMessage.class);

        assertThat(msg.commandId()).isEqualTo("TCMD-550e8400");
        assertThat(msg.vehicleId()).isEqualTo("REAL-F01");
        assertThat(msg.result()).isEqualTo("SUCCESS");
        assertThat(msg.completedAt()).isNotNull();
    }

    @Test
    void failResult_withExtraStageAndFailureCodeAndTaskId_isIgnoredNotRejected() throws Exception {
        // ROS2가 향후 확장 필드(stage/failureCode/taskId)를 함께 보내는 경우.
        String json = """
                {"commandId":"TCMD-550e8400","taskId":"TASK-001","vehicleId":"REAL-F01","result":"FAIL",
                 "message":"navigation to pickup failed","stage":"MOVING_TO_PICKUP",
                 "failureCode":"NAVIGATION_FAILED","completedAt":"2026-07-27T15:02:00+09:00"}
                """;

        // 미지 필드가 있어도 예외 없이 파싱되어야 한다(백엔드 계약 호환).
        VehicleCommandResultMessage msg = objectMapper.readValue(json, VehicleCommandResultMessage.class);

        assertThat(msg.commandId()).isEqualTo("TCMD-550e8400");
        assertThat(msg.vehicleId()).isEqualTo("REAL-F01");
        assertThat(msg.result()).isEqualTo("FAIL");
        assertThat(msg.message()).isEqualTo("navigation to pickup failed");
        assertThat(msg.completedAt()).isNotNull();
        // stage/failureCode/taskId는 DTO에 없어 조용히 무시된다(별도 저장 안 됨).
    }
}
