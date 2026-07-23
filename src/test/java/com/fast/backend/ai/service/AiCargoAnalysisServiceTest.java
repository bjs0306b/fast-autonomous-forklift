package com.fast.backend.ai.service;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.domain.AiCargoAnalysis;
import com.fast.backend.ai.domain.AiCargoDetectionBox;
import com.fast.backend.ai.dto.AiCargoAnalysisMessage;
import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import com.fast.backend.ai.mapper.AiCargoAnalysisMapper;
import com.fast.backend.ai.mapper.AiCargoDetectionBoxMapper;
import com.fast.backend.ai.websocket.AiCargoAnalysisBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiCargoAnalysisService.process()의 검증 순서·저장·브로드캐스트, 조회 메서드를 Mapper/Broadcaster를
 * 모킹해 검증한다(prompt26.md 16.4장). MyBatis/DB 실연동 검증은 AiCargoAnalysisMapperTest /
 * AiCargoDetectionBoxMapperTest가 담당한다.
 */
class AiCargoAnalysisServiceTest {

    private static final LocalDateTime PROCESSED_AT = LocalDateTime.of(2026, 7, 22, 13, 30, 1);

    private AiCargoAnalysisMapper analysisMapper;
    private AiCargoDetectionBoxMapper boxMapper;
    private AiCargoAnalysisBroadcaster broadcaster;
    private AiCargoAnalysisService service;

    @BeforeEach
    void setUp() {
        analysisMapper = mock(AiCargoAnalysisMapper.class);
        boxMapper = mock(AiCargoDetectionBoxMapper.class);
        broadcaster = mock(AiCargoAnalysisBroadcaster.class);
        service = new AiCargoAnalysisService(analysisMapper, boxMapper, broadcaster);
        when(analysisMapper.existsByAnalysisId(any())).thenReturn(false);
    }

    // ---------- 정상 흐름 ----------

    @Test
    void process_okMessage_savesAnalysisThenBoxesThenBroadcasts() {
        AiCargoAnalysisMessage message = okMessage("ANALYSIS-001", "CARGO-001");

        service.process(message);

        InOrder order = inOrder(analysisMapper, boxMapper, broadcaster);
        order.verify(analysisMapper).insert(any());
        order.verify(boxMapper, times(1)).insert(any());
        order.verify(broadcaster).broadcast(any());
    }

    @Test
    void process_okMessage_generatesReceivedAt() {
        AiCargoAnalysisMessage message = okMessage("ANALYSIS-002", "CARGO-002");

        service.process(message);

        ArgumentCaptor<AiCargoAnalysis> captor = ArgumentCaptor.forClass(AiCargoAnalysis.class);
        verify(analysisMapper).insert(captor.capture());
        assertThat(captor.getValue().getReceivedAt()).isNotNull();
        assertThat(captor.getValue().getProcessedAt()).isEqualTo(PROCESSED_AT);
    }

    @Test
    void process_registeredIdentifiers_doesNotCheckVehicleOrCargoExistence() {
        // prompt26.md 5장 11번 분석 결과: cargo/pallet/task 도메인 테이블이 없어 vehicleId/cargoId
        // 존재 확인을 하지 않는다 — 이 Service는 VehicleMapper 자체를 의존성으로 갖지 않는다.
        AiCargoAnalysisMessage message = okMessage("ANALYSIS-003", "CARGO-003");

        service.process(message);

        verify(analysisMapper).insert(any());
    }

    @Test
    void process_responseDto_matchesInputFields() {
        AiCargoAnalysisMessage message = okMessage("ANALYSIS-004", "CARGO-004");

        service.process(message);

        ArgumentCaptor<AiCargoAnalysisResponse> captor = ArgumentCaptor.forClass(AiCargoAnalysisResponse.class);
        verify(broadcaster).broadcast(captor.capture());
        AiCargoAnalysisResponse response = captor.getValue();
        assertThat(response.analysisId()).isEqualTo("ANALYSIS-004");
        assertThat(response.cargoId()).isEqualTo("CARGO-004");
        assertThat(response.status()).isEqualTo(AiAnalysisStatus.OK);
        assertThat(response.detection().boxes()).hasSize(1);
        assertThat(response.detection().boxes().get(0).bboxPx()).containsExactly(120, 80, 340, 260);
        assertThat(response.distance().valueCm()).isEqualTo(185.4);
        assertThat(response.dimensions().widthCm()).isEqualTo(120.0);
        assertThat(response.loadBalance().direction()).containsExactly(
                com.fast.backend.ai.domain.LoadBalanceDirection.LEFT,
                com.fast.backend.ai.domain.LoadBalanceDirection.FRONT);
        assertThat(response.ratios().horizontal()).isEqualTo(-0.31);
        assertThat(response.receivedAt()).isNotNull();
    }

    @Test
    void process_noDetectionMessage_nullSubObjectsInResponse() {
        AiCargoAnalysisMessage message = noDetectionMessage("ANALYSIS-005", "CARGO-005");

        service.process(message);

        ArgumentCaptor<AiCargoAnalysisResponse> captor = ArgumentCaptor.forClass(AiCargoAnalysisResponse.class);
        verify(broadcaster).broadcast(captor.capture());
        AiCargoAnalysisResponse response = captor.getValue();
        assertThat(response.status()).isEqualTo(AiAnalysisStatus.NO_DETECTION);
        assertThat(response.detection().boxes()).isEmpty();
        assertThat(response.dimensions()).isNull();
        assertThat(response.loadBalance()).isNull();
        assertThat(response.ratios()).isNull();
    }

    @Test
    void process_unreliableMessage_distancePresentDimensionsNull() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-006", "FORKLIFT-01", "CARGO-006", "unreliable",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.88, List.of(120, 80, 340, 260)))),
                new AiCargoAnalysisMessage.Distance(190.2, 38.4),
                null, null, null,
                "거리 측정 안정성이 낮습니다.", PROCESSED_AT.minusSeconds(1), PROCESSED_AT);

        service.process(message);

        ArgumentCaptor<AiCargoAnalysisResponse> captor = ArgumentCaptor.forClass(AiCargoAnalysisResponse.class);
        verify(broadcaster).broadcast(captor.capture());
        assertThat(captor.getValue().distance().stdCm()).isEqualTo(38.4);
        assertThat(captor.getValue().dimensions()).isNull();
    }

    // ---------- 중복·저장 실패 ----------

    @Test
    void process_duplicateAnalysisId_skipsInsertAndBroadcast() {
        when(analysisMapper.existsByAnalysisId("ANALYSIS-007")).thenReturn(true);
        AiCargoAnalysisMessage message = okMessage("ANALYSIS-007", "CARGO-007");

        service.process(message);

        verify(analysisMapper, never()).insert(any());
        verify(boxMapper, never()).insert(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void process_boxInsertFails_exceptionPropagatesForTransactionRollbackAndSkipsBroadcast() {
        doThrow(new RuntimeException("box insert failed")).when(boxMapper).insert(any());
        AiCargoAnalysisMessage message = okMessage("ANALYSIS-008", "CARGO-008");

        // @Transactional 메서드 밖으로 예외가 그대로 나가야 분석 결과 insert도 함께 롤백된다(9.4장) —
        // 이 예외를 최종적으로 삼키는 책임은 MqttMessageRouter에 있다(MqttMessageRouterTest 참고).
        assertThatThrownBy(() -> service.process(message)).hasMessage("box insert failed");
        verifyNoInteractions(broadcaster);
    }

    // ---------- schemaVersion ----------

    @Test
    void process_unsupportedSchemaVersion_skipsSaveAndBroadcast() {
        AiCargoAnalysisMessage message = withSchemaVersion(okMessage("ANALYSIS-009", "CARGO-009"), "2.0");

        service.process(message);

        verifyNoInteractions(analysisMapper, boxMapper, broadcaster);
    }

    // ---------- status ----------

    @Test
    void process_invalidStatus_skipsSaveAndBroadcast() {
        AiCargoAnalysisMessage message = withStatus(okMessage("ANALYSIS-010", "CARGO-010"), "moving");

        service.process(message);

        verifyNoInteractions(analysisMapper, boxMapper, broadcaster);
    }

    @Test
    void process_okStatusWithNoBoxes_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-011", "FORKLIFT-01", "CARGO-011", "ok",
                new AiCargoAnalysisMessage.Detection(List.of()),
                null, null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_noDetectionStatusWithNonEmptyBoxes_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-012", "FORKLIFT-01", "CARGO-012", "no_detection",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_noDetectionStatusWithNonNullDimensions_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-013", "FORKLIFT-01", "CARGO-013", "no_detection",
                new AiCargoAnalysisMessage.Detection(List.of()),
                null,
                new AiCargoAnalysisMessage.Dimensions(120.0, 85.0, null, null, "REAL"),
                null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    // ---------- bbox ----------

    @Test
    void process_bboxWrongLength_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-014", "FORKLIFT-01", "CARGO-014", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3)))),
                null, null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_bboxNonPositiveWidth_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-015", "FORKLIFT-01", "CARGO-015", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 0, 4)))),
                null, null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_confidenceOutOfRange_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-016", "FORKLIFT-01", "CARGO-016", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 1.5, List.of(1, 2, 3, 4)))),
                null, null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_blankClassName_normalizesToUnknownAndStillSaves() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-017", "FORKLIFT-01", "CARGO-017", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox(" ", 0.9, List.of(1, 2, 3, 4)))),
                null, null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        ArgumentCaptor<AiCargoDetectionBox> captor = ArgumentCaptor.forClass(AiCargoDetectionBox.class);
        verify(boxMapper).insert(captor.capture());
        assertThat(captor.getValue().getClassName()).isEqualTo("unknown");
    }

    // ---------- distance ----------

    @Test
    void process_nanDistance_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-018", "FORKLIFT-01", "CARGO-018", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                new AiCargoAnalysisMessage.Distance(Double.NaN, null),
                null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_negativeDistanceValue_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-019", "FORKLIFT-01", "CARGO-019", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                new AiCargoAnalysisMessage.Distance(-5.0, null),
                null, null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    // ---------- dimensions ----------

    @Test
    void process_dimensionsInvalidScale_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-020", "FORKLIFT-01", "CARGO-020", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null,
                new AiCargoAnalysisMessage.Dimensions(120.0, 85.0, null, null, "HALF"),
                null, null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_dimensionsNullDepthAndVolume_stillSaves() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-021", "FORKLIFT-01", "CARGO-021", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null,
                new AiCargoAnalysisMessage.Dimensions(120.0, 85.0, null, null, "REAL"),
                null, null, null, null, PROCESSED_AT);

        service.process(message);

        verify(analysisMapper).insert(any());
    }

    // ---------- loadBalance ----------

    @Test
    void process_oppositeDirections_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-022", "FORKLIFT-01", "CARGO-022", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null,
                new AiCargoAnalysisMessage.LoadBalance(List.of("left", "right"), "msg"),
                null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_duplicateDirection_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-023", "FORKLIFT-01", "CARGO-023", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null,
                new AiCargoAnalysisMessage.LoadBalance(List.of("left", "left"), "msg"),
                null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_unknownDirection_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-024", "FORKLIFT-01", "CARGO-024", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null,
                new AiCargoAnalysisMessage.LoadBalance(List.of("up"), "msg"),
                null, null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_diagonalDirections_isAccepted() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-025", "FORKLIFT-01", "CARGO-025", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null,
                new AiCargoAnalysisMessage.LoadBalance(List.of("left", "back"), "msg"),
                null, null, null, PROCESSED_AT);

        service.process(message);

        verify(analysisMapper).insert(any());
    }

    @Test
    void process_emptyDirectionArray_isAccepted() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-026", "FORKLIFT-01", "CARGO-026", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null,
                new AiCargoAnalysisMessage.LoadBalance(List.of(), "균형 상태입니다."),
                null, null, null, PROCESSED_AT);

        service.process(message);

        verify(analysisMapper).insert(any());
    }

    // ---------- ratios ----------

    @Test
    void process_infiniteRatio_rejected() {
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-027", "FORKLIFT-01", "CARGO-027", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null, null,
                new AiCargoAnalysisMessage.Ratios(Double.POSITIVE_INFINITY, 0.1),
                null, null, PROCESSED_AT);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_negativeRatio_isAcceptedAsIs() {
        // 부호는 방향을 의미하므로 음수 자체는 유효하다(prompt26.md 2.6장) — NaN/Infinity만 거부한다.
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                "1.0", "ANALYSIS-028", "FORKLIFT-01", "CARGO-028", "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.9, List.of(1, 2, 3, 4)))),
                null, null, null,
                new AiCargoAnalysisMessage.Ratios(-0.9, -0.5),
                null, null, PROCESSED_AT);

        service.process(message);

        ArgumentCaptor<AiCargoAnalysis> captor = ArgumentCaptor.forClass(AiCargoAnalysis.class);
        verify(analysisMapper).insert(captor.capture());
        assertThat(captor.getValue().getRatioHorizontal()).isEqualTo(-0.9);
    }

    // ---------- 필수값 ----------

    @Test
    void process_blankAnalysisId_rejected() {
        AiCargoAnalysisMessage message = withAnalysisId(okMessage("ANALYSIS-029", "CARGO-029"), " ");

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    @Test
    void process_nullProcessedAt_rejected() {
        AiCargoAnalysisMessage base = okMessage("ANALYSIS-030", "CARGO-030");
        AiCargoAnalysisMessage message = new AiCargoAnalysisMessage(
                base.schemaVersion(), base.analysisId(), base.vehicleId(), base.cargoId(), base.status(),
                base.detection(), base.distance(), base.dimensions(), base.loadBalance(), base.ratios(),
                base.message(), base.capturedAt(), null);

        service.process(message);

        verifyNoInteractions(analysisMapper, broadcaster);
    }

    // ---------- 조회 ----------

    @Test
    void findByAnalysisId_existingId_returnsResponseWithBoxes() {
        AiCargoAnalysis analysis = storedAnalysis("ANALYSIS-031", "CARGO-031");
        when(analysisMapper.findByAnalysisId("ANALYSIS-031")).thenReturn(Optional.of(analysis));
        when(boxMapper.findByAnalysisId(analysis.getId())).thenReturn(List.of());

        AiCargoAnalysisResponse response = service.findByAnalysisId("ANALYSIS-031");

        assertThat(response.analysisId()).isEqualTo("ANALYSIS-031");
    }

    @Test
    void findByAnalysisId_missingId_throwsNotFound() {
        when(analysisMapper.findByAnalysisId("NO-SUCH")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByAnalysisId("NO-SUCH"))
                .isInstanceOf(com.fast.backend.common.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", com.fast.backend.common.exception.ErrorCode.AI_ANALYSIS_NOT_FOUND);
    }

    @Test
    void findLatestByCargoId_missingCargo_throwsNotFound() {
        when(analysisMapper.findLatestByCargoId("NO-SUCH-CARGO")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findLatestByCargoId("NO-SUCH-CARGO"))
                .isInstanceOf(com.fast.backend.common.exception.BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", com.fast.backend.common.exception.ErrorCode.AI_ANALYSIS_NOT_FOUND);
    }

    // ---------- 헬퍼 ----------

    private AiCargoAnalysisMessage okMessage(String analysisId, String cargoId) {
        return new AiCargoAnalysisMessage(
                "1.0", analysisId, "FORKLIFT-01", cargoId, "ok",
                new AiCargoAnalysisMessage.Detection(List.of(
                        new AiCargoAnalysisMessage.DetectedBox("box", 0.96, List.of(120, 80, 340, 260)))),
                new AiCargoAnalysisMessage.Distance(185.4, 2.8),
                new AiCargoAnalysisMessage.Dimensions(120.0, 85.0, null, null, "REAL"),
                new AiCargoAnalysisMessage.LoadBalance(List.of("left", "front"), "무게 중심이 좌측 전방으로 치우쳐 있습니다."),
                new AiCargoAnalysisMessage.Ratios(-0.31, 0.18),
                "화물 분석이 완료되었습니다.", PROCESSED_AT.minusSeconds(1), PROCESSED_AT);
    }

    private AiCargoAnalysisMessage noDetectionMessage(String analysisId, String cargoId) {
        return new AiCargoAnalysisMessage(
                "1.0", analysisId, "FORKLIFT-01", cargoId, "no_detection",
                new AiCargoAnalysisMessage.Detection(List.of()),
                null, null, null, null,
                "박스 또는 파렛트를 찾지 못했습니다.", PROCESSED_AT.minusSeconds(1), PROCESSED_AT);
    }

    private AiCargoAnalysisMessage withSchemaVersion(AiCargoAnalysisMessage base, String schemaVersion) {
        return new AiCargoAnalysisMessage(
                schemaVersion, base.analysisId(), base.vehicleId(), base.cargoId(), base.status(),
                base.detection(), base.distance(), base.dimensions(), base.loadBalance(), base.ratios(),
                base.message(), base.capturedAt(), base.processedAt());
    }

    private AiCargoAnalysisMessage withStatus(AiCargoAnalysisMessage base, String status) {
        return new AiCargoAnalysisMessage(
                base.schemaVersion(), base.analysisId(), base.vehicleId(), base.cargoId(), status,
                base.detection(), base.distance(), base.dimensions(), base.loadBalance(), base.ratios(),
                base.message(), base.capturedAt(), base.processedAt());
    }

    private AiCargoAnalysisMessage withAnalysisId(AiCargoAnalysisMessage base, String analysisId) {
        return new AiCargoAnalysisMessage(
                base.schemaVersion(), analysisId, base.vehicleId(), base.cargoId(), base.status(),
                base.detection(), base.distance(), base.dimensions(), base.loadBalance(), base.ratios(),
                base.message(), base.capturedAt(), base.processedAt());
    }

    private AiCargoAnalysis storedAnalysis(String analysisId, String cargoId) {
        AiCargoAnalysis analysis = new AiCargoAnalysis();
        analysis.setId(1L);
        analysis.setAnalysisId(analysisId);
        analysis.setCargoId(cargoId);
        analysis.setStatus(AiAnalysisStatus.OK);
        analysis.setProcessedAt(PROCESSED_AT);
        analysis.setReceivedAt(PROCESSED_AT);
        return analysis;
    }
}
