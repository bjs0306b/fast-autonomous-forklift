package com.fast.backend.ai.controller;

import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import com.fast.backend.ai.service.AiCargoAnalysisService;
import com.fast.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 화물 분석 결과 조회 API(prompt26.md 12장). 새 분석 결과 수신은 REST가 아니라 MQTT
 * ({@code cargo/detected} 토픽, {@code AiCargoAnalysisService#process})로만 이뤄진다 — 이 Controller는
 * 조회 전용이다. 화면 요구사항이 analysisId 단건 조회와 cargoId 최신 조회 두 가지로 좁혀져(12장
 * "권장 최소 구현" + "화면에 필요하다면") 이 두 엔드포인트만 구현했다.
 */
@RestController
@RequestMapping("/api")
public class AiCargoAnalysisController {

    private final AiCargoAnalysisService aiCargoAnalysisService;

    public AiCargoAnalysisController(AiCargoAnalysisService aiCargoAnalysisService) {
        this.aiCargoAnalysisService = aiCargoAnalysisService;
    }

    @GetMapping("/ai/cargo-analysis/{analysisId}")
    public ApiResponse<AiCargoAnalysisResponse> getByAnalysisId(@PathVariable String analysisId) {
        return ApiResponse.success(aiCargoAnalysisService.findByAnalysisId(analysisId));
    }

    @GetMapping("/cargos/{cargoId}/ai-analysis/latest")
    public ApiResponse<AiCargoAnalysisResponse> getLatestByCargoId(@PathVariable String cargoId) {
        return ApiResponse.success(aiCargoAnalysisService.findLatestByCargoId(cargoId));
    }
}
