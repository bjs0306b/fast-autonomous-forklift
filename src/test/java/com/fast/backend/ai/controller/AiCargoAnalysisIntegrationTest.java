package com.fast.backend.ai.controller;

import com.fast.backend.ai.dto.AiCargoAnalysisMessage;
import com.fast.backend.ai.service.AiCargoAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AiCargoAnalysisService.process() → 실제 MyBatis/H2 저장 → REST 조회 API까지 실제 Spring 컨텍스트로
 * 관통하는 종단 테스트(VehicleControllerIntegrationTest와 동일 스타일). PUT/POST로 MQTT 메시지를 흉내내는
 * REST 진입점이 이 기능에는 없으므로(prompt26.md 6장, MQTT 전용 수신), Service를 직접 호출해 MQTT
 * 진입점을 흉내낸다 — MqttMessageRouter 자체의 배선 검증은 MqttMessageRouterTest(Mock 기반)가 담당한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiCargoAnalysisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiCargoAnalysisService aiCargoAnalysisService;

    @Test
    void process_thenGetByAnalysisId_returnsStoredResult() throws Exception {
        AiCargoAnalysisMessage message = okMessage("IT-ANALYSIS-01", "IT-CARGO-01");

        aiCargoAnalysisService.process(message);

        mockMvc.perform(get("/api/ai/cargo-analysis/IT-ANALYSIS-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.analysisId").value("IT-ANALYSIS-01"))
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.data.detection.boxes.length()").value(1))
                .andExpect(jsonPath("$.data.detection.boxes[0].bboxPx[0]").value(120))
                .andExpect(jsonPath("$.data.loadBalance.direction[0]").value("left"));
    }

    @Test
    void process_thenGetLatestByCargoId_returnsMostRecent() throws Exception {
        aiCargoAnalysisService.process(okMessage("IT-ANALYSIS-02", "IT-CARGO-02"));

        mockMvc.perform(get("/api/cargos/IT-CARGO-02/ai-analysis/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value("IT-ANALYSIS-02"));
    }

    @Test
    void getByAnalysisId_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/ai/cargo-analysis/NO-SUCH-ANALYSIS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("AI_ANALYSIS_NOT_FOUND"));
    }

    @Test
    void process_duplicateAnalysisId_doesNotOverwriteFirst() throws Exception {
        aiCargoAnalysisService.process(okMessage("IT-ANALYSIS-03", "IT-CARGO-03"));
        aiCargoAnalysisService.process(okMessage("IT-ANALYSIS-03", "IT-CARGO-03"));

        mockMvc.perform(get("/api/ai/cargo-analysis/IT-ANALYSIS-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.analysisId").value("IT-ANALYSIS-03"));
    }

    @Test
    void process_noDetectionMessage_thenGet_returnsNullSubObjectsInJson() throws Exception {
        LocalDateTime processedAt = LocalDateTime.now();
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "IT-ANALYSIS-04", "FORKLIFT-01", "IT-CARGO-04", "no_detection",
                new AiCargoAnalysisMessage.Detection(List.of()),
                null, null, null, null,
                "박스 또는 파렛트를 찾지 못했습니다.", processedAt.minusSeconds(1), processedAt);

        aiCargoAnalysisService.process(message);

        mockMvc.perform(get("/api/ai/cargo-analysis/IT-ANALYSIS-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("no_detection"))
                .andExpect(jsonPath("$.data.detection.boxes.length()").value(0))
                .andExpect(jsonPath("$.data.dimensions").doesNotExist())
                .andExpect(jsonPath("$.data.loadBalance").doesNotExist())
                .andExpect(jsonPath("$.data.ratios").doesNotExist());
    }

    /**
     * prompt27.md 6장 "저장 중 감지 박스 insert 실패 시 전체 롤백" / "분석 본문만 남고 감지 박스가
     * 누락되는 불완전 저장이 발생하지 않는지"를 실제 H2 트랜잭션으로 검증한다. 클래스 레벨
     * {@code @Transactional}(REQUIRED)에 참여하면 Service의 실패가 즉시 물리적으로 롤백되지 않고
     * 테스트 종료 시점까지 지연되어(같은 트랜잭션 공유) 이 검증이 무의미해지므로, 이 테스트만
     * {@code NOT_SUPPORTED}로 트랜잭션을 잠시 걷어내 Service의 {@code @Transactional}이 독립된 실제
     * 트랜잭션으로 커밋/롤백되게 한 뒤 그 결과를 별도 커넥션으로 확인한다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void process_secondBoxInsertFailsAtDbLevel_rollsBackAnalysisAndFirstBoxToo() throws Exception {
        String analysisId = "IT-ROLLBACK-01";
        LocalDateTime processedAt = LocalDateTime.now();
        String oversizedClassName = "A".repeat(300); // class_name VARCHAR(50)을 초과시켜 실제 DB 오류 유발
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", analysisId, "FORKLIFT-01", "IT-CARGO-ROLLBACK", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)),
                        new AiCargoAnalysisMessage.DetectedBox(oversizedClassName, 0.9, List.of(5, 6, 7, 8)))),
                null, null, null, null,
                "롤백 테스트", processedAt.minusSeconds(1), processedAt);

        assertThatThrownBy(() -> aiCargoAnalysisService.process(message))
                .isInstanceOf(RuntimeException.class);

        // 별도(트랜잭션 없는) 커넥션으로 조회 — 분석 결과 본문도, 먼저 성공했던 첫 번째 박스도 전혀
        // 남아있지 않아야 한다(부분 성공 없음).
        mockMvc.perform(get("/api/ai/cargo-analysis/" + analysisId))
                .andExpect(status().isNotFound());
    }

    private AiCargoAnalysisMessage okMessage(String analysisId, String cargoId) {
        LocalDateTime processedAt = LocalDateTime.now();
        return new AiCargoAnalysisMessage(
                "1.0", analysisId, "FORKLIFT-01", cargoId, "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.96, List.of(120, 80, 340, 260)))),
                new AiCargoAnalysisMessage.Distance(185.4, 2.8),
                new AiCargoAnalysisMessage.Dimensions(120.0, 85.0, null, null, "REAL"),
                new AiCargoAnalysisMessage.LoadBalance(List.of("left", "front"), "무게 중심이 좌측 전방으로 치우쳐 있습니다."),
                new AiCargoAnalysisMessage.Ratios(-0.31, 0.18),
                "화물 분석이 완료되었습니다.", processedAt.minusSeconds(1), processedAt);
    }
}
