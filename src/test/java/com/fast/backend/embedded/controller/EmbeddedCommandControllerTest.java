package com.fast.backend.embedded.controller;

import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.embedded.dto.EmbeddedCommandRequest;
import com.fast.backend.embedded.dto.EmbeddedCommandResponse;
import com.fast.backend.embedded.dto.EmbeddedErrorResponse;
import com.fast.backend.embedded.dto.EmbeddedForkStatusResponse;
import com.fast.backend.embedded.service.EmbeddedCommandService;
import com.fast.backend.embedded.service.EmbeddedErrorService;
import com.fast.backend.embedded.service.EmbeddedForkStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service를 모킹해 Controller가 요청/응답을 올바르게 위임·포장하는지만 검증한다(prompt29.md 18장,
 * AiCargoAnalysisControllerTest와 동일한 이 저장소의 기존 단위 테스트 스타일).
 */
class EmbeddedCommandControllerTest {

    private EmbeddedCommandService commandService;
    private EmbeddedForkStatusService forkStatusService;
    private EmbeddedErrorService errorService;
    private EmbeddedCommandController controller;

    @BeforeEach
    void setUp() {
        commandService = mock(EmbeddedCommandService.class);
        forkStatusService = mock(EmbeddedForkStatusService.class);
        errorService = mock(EmbeddedErrorService.class);
        controller = new EmbeddedCommandController(commandService, forkStatusService, errorService);
    }

    @Test
    void issueCommand_returns201AndServiceResultWrapped() {
        EmbeddedCommandResponse expected = commandResponse("CMD-001");
        when(commandService.issueCommand("REAL01", "FORK_UP", "화물 상차")).thenReturn(expected);

        ResponseEntity<ApiResponse<EmbeddedCommandResponse>> response =
                controller.issueCommand("REAL01", new EmbeddedCommandRequest("FORK_UP", "화물 상차"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).isEqualTo(expected);
    }

    @Test
    void issueCommand_unknownCommand_propagatesBusinessException() {
        when(commandService.issueCommand("REAL01", "FLY", null))
                .thenThrow(new BusinessException(ErrorCode.EMBEDDED_COMMAND_TYPE_INVALID, "알 수 없는 명령입니다."));

        assertThatThrownBy(() -> controller.issueCommand("REAL01", new EmbeddedCommandRequest("FLY", null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_TYPE_INVALID);
    }

    @Test
    void getCommand_returnsServiceResultWrapped() {
        EmbeddedCommandResponse expected = commandResponse("CMD-001");
        when(commandService.findByCommandId("REAL01", "CMD-001")).thenReturn(expected);

        ApiResponse<EmbeddedCommandResponse> response = controller.getCommand("REAL01", "CMD-001");

        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void getCommand_notFound_propagatesBusinessException() {
        when(commandService.findByCommandId("REAL01", "NO-SUCH"))
                .thenThrow(new BusinessException(ErrorCode.EMBEDDED_COMMAND_NOT_FOUND, "존재하지 않는 명령입니다."));

        assertThatThrownBy(() -> controller.getCommand("REAL01", "NO-SUCH"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_COMMAND_NOT_FOUND);
    }

    @Test
    void listCommands_defaultLimit_passesFiftyToService() {
        when(commandService.findRecentByForkliftId("REAL01", 50)).thenReturn(List.of(commandResponse("CMD-001")));

        ApiResponse<List<EmbeddedCommandResponse>> response = controller.listCommands("REAL01", 50);

        assertThat(response.getData()).hasSize(1);
    }

    @Test
    void getForkStatus_returnsServiceResultWrapped() {
        EmbeddedForkStatusResponse expected = new EmbeddedForkStatusResponse(
                "REAL01", "STOPPED", false, null, LocalDateTime.now(), LocalDateTime.now());
        when(forkStatusService.getCurrentForkStatus("REAL01")).thenReturn(expected);

        ApiResponse<EmbeddedForkStatusResponse> response = controller.getForkStatus("REAL01");

        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void getForkStatus_notFound_propagatesBusinessException() {
        when(forkStatusService.getCurrentForkStatus("REAL01"))
                .thenThrow(new BusinessException(ErrorCode.EMBEDDED_FORK_STATUS_NOT_FOUND, "포크 상태 정보가 없습니다."));

        assertThatThrownBy(() -> controller.getForkStatus("REAL01"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMBEDDED_FORK_STATUS_NOT_FOUND);
    }

    @Test
    void listErrors_returnsServiceResultWrapped() {
        EmbeddedErrorResponse expected = new EmbeddedErrorResponse(
                1L, "REAL01", "E001", "DRIVE", "WARNING", "메시지", LocalDateTime.now(), LocalDateTime.now());
        when(errorService.findRecentByForkliftId("REAL01", 50)).thenReturn(List.of(expected));

        ApiResponse<List<EmbeddedErrorResponse>> response = controller.listErrors("REAL01", 50);

        assertThat(response.getData()).containsExactly(expected);
    }

    private EmbeddedCommandResponse commandResponse(String commandId) {
        return new EmbeddedCommandResponse(
                commandId, "REAL01", "FORK_UP", "PUBLISHED", "화물 상차",
                LocalDateTime.now(), LocalDateTime.now(), null, null, null,
                List.of(), null, null);
    }
}
