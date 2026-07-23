package com.fast.backend.ai.controller;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import com.fast.backend.ai.service.AiCargoAnalysisService;
import com.fast.backend.common.api.ApiResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiCargoAnalysisService를 모킹해 Controller가 요청/응답을 올바르게 위임·포장하는지만 검증한다
 * (VehicleControllerTest와 동일한 이 저장소의 기존 단위 테스트 스타일).
 */
class AiCargoAnalysisControllerTest {

    private AiCargoAnalysisService service;
    private AiCargoAnalysisController controller;

    @BeforeEach
    void setUp() {
        service = mock(AiCargoAnalysisService.class);
        controller = new AiCargoAnalysisController(service);
    }

    @Test
    void getByAnalysisId_returnsServiceResultWrapped() {
        AiCargoAnalysisResponse expected = response("ANALYSIS-001", "CARGO-001");
        when(service.findByAnalysisId("ANALYSIS-001")).thenReturn(expected);

        ApiResponse<AiCargoAnalysisResponse> response = controller.getByAnalysisId("ANALYSIS-001");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(expected);
    }

    @Test
    void getByAnalysisId_notFound_propagatesBusinessException() {
        when(service.findByAnalysisId("NO-SUCH"))
                .thenThrow(new BusinessException(ErrorCode.AI_ANALYSIS_NOT_FOUND, "not found"));

        assertThatThrownBy(() -> controller.getByAnalysisId("NO-SUCH"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_ANALYSIS_NOT_FOUND);
    }

    @Test
    void getLatestByCargoId_returnsServiceResultWrapped() {
        AiCargoAnalysisResponse expected = response("ANALYSIS-002", "CARGO-002");
        when(service.findLatestByCargoId("CARGO-002")).thenReturn(expected);

        ApiResponse<AiCargoAnalysisResponse> response = controller.getLatestByCargoId("CARGO-002");

        assertThat(response.getData()).isEqualTo(expected);
    }

    private AiCargoAnalysisResponse response(String analysisId, String cargoId) {
        return new AiCargoAnalysisResponse(
                analysisId, "FORKLIFT-01", cargoId, AiAnalysisStatus.OK,
                new AiCargoAnalysisResponse.Detection(java.util.List.of()),
                null, null, null, null,
                "메시지", LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
