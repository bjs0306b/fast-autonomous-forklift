package com.fast.backend.ai.mapper;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.domain.AiCargoAnalysis;
import com.fast.backend.ai.domain.DimensionScale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ai_cargo_analysis insert/조회, analysis_id UNIQUE 제약, null dimensions/depth 저장이 실제 H2(MySQL
 * 호환 모드)에서 동작하는지 검증한다(prompt26.md 16.3장).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AiCargoAnalysisMapperTest {

    @Autowired
    private AiCargoAnalysisMapper mapper;

    @Test
    void insert_generatesId() {
        AiCargoAnalysis analysis = newAnalysis("ANALYSIS-01", "CARGO-01", AiAnalysisStatus.OK);

        mapper.insert(analysis);

        assertThat(analysis.getId()).isNotNull();
    }

    @Test
    void findByAnalysisId_returnsInsertedRow() {
        mapper.insert(newAnalysis("ANALYSIS-02", "CARGO-02", AiAnalysisStatus.OK));

        Optional<AiCargoAnalysis> found = mapper.findByAnalysisId("ANALYSIS-02");

        assertThat(found).isPresent();
        assertThat(found.get().getCargoId()).isEqualTo("CARGO-02");
        assertThat(found.get().getStatus()).isEqualTo(AiAnalysisStatus.OK);
    }

    @Test
    void insert_duplicateAnalysisId_violatesDbUniqueConstraint() {
        // 애플리케이션 사전 확인(existsByAnalysisId)을 일부러 건너뛰고 같은 analysisId를 두 번 insert해,
        // DB UNIQUE 제약 자체가 실제로 이중 방어로 동작하는지 검증한다(prompt27.md 5장
        // "애플리케이션 사전 조회와 DB UNIQUE가 이중 방어로 동작하는지").
        mapper.insert(newAnalysis("ANALYSIS-DUP", "CARGO-DUP", AiAnalysisStatus.OK));

        assertThatThrownBy(() -> mapper.insert(newAnalysis("ANALYSIS-DUP", "CARGO-DUP", AiAnalysisStatus.OK)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void existsByAnalysisId_detectsDuplicate() {
        mapper.insert(newAnalysis("ANALYSIS-03", "CARGO-03", AiAnalysisStatus.OK));

        assertThat(mapper.existsByAnalysisId("ANALYSIS-03")).isTrue();
        assertThat(mapper.existsByAnalysisId("ANALYSIS-NO-SUCH")).isFalse();
    }

    @Test
    void insert_nullDimensionsAndDepth_storesNull() {
        AiCargoAnalysis analysis = newAnalysis("ANALYSIS-04", "CARGO-04", AiAnalysisStatus.NO_DETECTION);
        analysis.setWidthCm(null);
        analysis.setHeightCm(null);
        analysis.setDepthCm(null);
        analysis.setVolumeCm3(null);
        analysis.setDimensionScale(null);

        mapper.insert(analysis);

        AiCargoAnalysis saved = mapper.findByAnalysisId("ANALYSIS-04").orElseThrow();
        assertThat(saved.getWidthCm()).isNull();
        assertThat(saved.getDepthCm()).isNull();
        assertThat(saved.getDimensionScale()).isNull();
    }

    @Test
    void insert_loadDirectionAndScaleEnum_roundTrips() {
        AiCargoAnalysis analysis = newAnalysis("ANALYSIS-05", "CARGO-05", AiAnalysisStatus.OK);
        analysis.setLoadDirection("LEFT,FRONT");
        analysis.setDimensionScale(DimensionScale.MINIATURE);

        mapper.insert(analysis);

        AiCargoAnalysis saved = mapper.findByAnalysisId("ANALYSIS-05").orElseThrow();
        assertThat(saved.getLoadDirection()).isEqualTo("LEFT,FRONT");
        assertThat(saved.getDimensionScale()).isEqualTo(DimensionScale.MINIATURE);
    }

    @Test
    void findLatestByCargoId_returnsMostRecentByProcessedAt() {
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(2);
        LocalDateTime t2 = LocalDateTime.now();
        AiCargoAnalysis older = newAnalysis("ANALYSIS-06", "CARGO-06", AiAnalysisStatus.OK);
        older.setProcessedAt(t1);
        AiCargoAnalysis newer = newAnalysis("ANALYSIS-07", "CARGO-06", AiAnalysisStatus.OK);
        newer.setProcessedAt(t2);
        mapper.insert(older);
        mapper.insert(newer);

        AiCargoAnalysis latest = mapper.findLatestByCargoId("CARGO-06").orElseThrow();

        assertThat(latest.getAnalysisId()).isEqualTo("ANALYSIS-07");
    }

    @Test
    void findLatestByCargoId_separatesDifferentCargoIds() {
        mapper.insert(newAnalysis("ANALYSIS-08", "CARGO-08", AiAnalysisStatus.OK));
        mapper.insert(newAnalysis("ANALYSIS-09", "CARGO-09", AiAnalysisStatus.OK));

        AiCargoAnalysis latest = mapper.findLatestByCargoId("CARGO-08").orElseThrow();

        assertThat(latest.getAnalysisId()).isEqualTo("ANALYSIS-08");
    }

    private AiCargoAnalysis newAnalysis(String analysisId, String cargoId, AiAnalysisStatus status) {
        LocalDateTime now = LocalDateTime.now();
        AiCargoAnalysis analysis = new AiCargoAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setSchemaVersion("1.0");
        analysis.setVehicleId("FORKLIFT-01");
        analysis.setCargoId(cargoId);
        analysis.setStatus(status);
        analysis.setWidthCm(120.0);
        analysis.setHeightCm(85.0);
        analysis.setDimensionScale(DimensionScale.REAL);
        analysis.setProcessedAt(now);
        analysis.setReceivedAt(now);
        analysis.setCreatedAt(now);
        return analysis;
    }
}
