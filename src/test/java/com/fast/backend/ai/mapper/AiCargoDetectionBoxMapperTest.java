package com.fast.backend.ai.mapper;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.domain.AiCargoAnalysis;
import com.fast.backend.ai.domain.AiCargoDetectionBox;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai_cargo_detection_box 다중 insert·조회, analysis_id FK가 실제 H2에서 동작하는지 검증한다
 * (prompt26.md 16.3장).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiCargoDetectionBoxMapperTest {

    @Autowired
    private AiCargoAnalysisMapper analysisMapper;

    @Autowired
    private AiCargoDetectionBoxMapper boxMapper;

    @Test
    void insert_generatesId() {
        Long analysisId = insertAnalysis("ANALYSIS-BOX-01");
        AiCargoDetectionBox box = newBox(analysisId, "box", 0.96, 120, 80, 340, 260);

        boxMapper.insert(box);

        assertThat(box.getId()).isNotNull();
    }

    @Test
    void insert_multipleBoxes_allStoredForSameAnalysis() {
        Long analysisId = insertAnalysis("ANALYSIS-BOX-02");
        boxMapper.insert(newBox(analysisId, "box", 0.96, 120, 80, 340, 260));
        boxMapper.insert(newBox(analysisId, "pallet", 0.93, 90, 240, 410, 160));

        List<AiCargoDetectionBox> boxes = boxMapper.findByAnalysisId(analysisId);

        assertThat(boxes).hasSize(2);
        assertThat(boxes).extracting(AiCargoDetectionBox::getClassName).containsExactly("box", "pallet");
    }

    @Test
    void findByAnalysisId_separatesDifferentAnalyses() {
        Long analysisId1 = insertAnalysis("ANALYSIS-BOX-03");
        Long analysisId2 = insertAnalysis("ANALYSIS-BOX-04");
        boxMapper.insert(newBox(analysisId1, "box", 0.9, 1, 2, 3, 4));
        boxMapper.insert(newBox(analysisId2, "pallet", 0.9, 5, 6, 7, 8));

        List<AiCargoDetectionBox> boxes = boxMapper.findByAnalysisId(analysisId1);

        assertThat(boxes).extracting(AiCargoDetectionBox::getAnalysisId).containsOnly(analysisId1);
    }

    @Test
    void insert_nullConfidence_isAllowed() {
        Long analysisId = insertAnalysis("ANALYSIS-BOX-05");
        AiCargoDetectionBox box = newBox(analysisId, "box", null, 1, 2, 3, 4);

        boxMapper.insert(box);

        AiCargoDetectionBox saved = boxMapper.findByAnalysisId(analysisId).get(0);
        assertThat(saved.getConfidence()).isNull();
    }

    private Long insertAnalysis(String analysisId) {
        LocalDateTime now = LocalDateTime.now();
        AiCargoAnalysis analysis = new AiCargoAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setSchemaVersion("1.0");
        analysis.setCargoId("CARGO-BOX");
        analysis.setStatus(AiAnalysisStatus.OK);
        analysis.setProcessedAt(now);
        analysis.setReceivedAt(now);
        analysis.setCreatedAt(now);
        analysisMapper.insert(analysis);
        return analysis.getId();
    }

    private AiCargoDetectionBox newBox(Long analysisId, String className, Double confidence,
            int x, int y, int width, int height) {
        AiCargoDetectionBox box = new AiCargoDetectionBox();
        box.setAnalysisId(analysisId);
        box.setClassName(className);
        box.setConfidence(confidence);
        box.setBboxX(x);
        box.setBboxY(y);
        box.setBboxWidth(width);
        box.setBboxHeight(height);
        box.setCreatedAt(LocalDateTime.now());
        return box;
    }
}
