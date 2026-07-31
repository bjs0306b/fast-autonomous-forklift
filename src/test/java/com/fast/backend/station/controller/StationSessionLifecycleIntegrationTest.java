package com.fast.backend.station.controller;

import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 수명주기 종단 테스트(prompt96.md 23장) — 실제 Controller·Service·Mapper·H2 제약을 모두 태운다.
 *
 * <p>고정하려는 것은 <b>순서 강제</b>다: 세션 시작 → 측정 등록 → 세션 종료 → 다음 세션.
 * 이 순서를 어기는 요청(측정 없이 종료, 활성 중 새 세션, 세션당 두 번째 측정)이 전부 409 로 막히고,
 * <b>막힌 뒤에도 활성 세션이 그대로 남아 있는지</b>까지 확인한다 — 실패한 요청이 상태를 흔들면
 * 다음 정상 요청이 엉뚱하게 동작한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StationSessionLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private StationMeasurementMapper measurementMapper;

    @BeforeEach
    void seedCargo() {
        insertCargo("SSL-CARGO-1");
        insertCargo("SSL-CARGO-2");
    }

    @Test
    void fullHappyPath_openMeasureCloseThenNextSessionCanStart() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        postMeasurement("M-OK-1", okBody(sessionId, "M-OK-1", 0.723, "safe", 0.02))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.cargoId").value("SSL-CARGO-1"))
                .andExpect(jsonPath("$.data.tippingLevel").value("SAFE"))
                .andExpect(jsonPath("$.data.cargoHeight").value(0.723))
                // 세 안전값이 모두 정상이므로 적재 추천 대상이다.
                .andExpect(jsonPath("$.data.placementEligible").value(true));

        mockMvc.perform(delete("/api/stations/sessions/{id}", sessionId))
                .andExpect(status().isOk());

        // 종료됐으므로 다음 세션을 시작할 수 있다.
        openSession("SSL-CARGO-2");
    }

    @Test
    void closingBeforeMeasurement_is409AndKeepsSessionActive() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        mockMvc.perform(delete("/api/stations/sessions/{id}", sessionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // 실패한 종료가 활성 세션을 풀어 버리면 안 된다.
        mockMvc.perform(get("/api/stations/sessions/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId));

        // 여전히 점유 중이므로 새 세션도 시작할 수 없다.
        mockMvc.perform(post("/api/stations/sessions").param("cargoId", "SSL-CARGO-2"))
                .andExpect(status().isConflict());
    }

    @Test
    void openingWhileActive_is409AndOriginalSessionSurvives() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        mockMvc.perform(post("/api/stations/sessions").param("cargoId", "SSL-CARGO-2"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/stations/sessions/active"))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId));
    }

    @Test
    void secondMeasurementInSameSession_is409AndFirstResultIsKept() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");
        postMeasurement("M-FIRST", okBody(sessionId, "M-FIRST", 0.5, "safe", 0.01))
                .andExpect(status().isCreated());

        // 다른 measurementId 지만 같은 세션 — measurementId 중복과 다른 오류여야 한다.
        postMeasurement("M-SECOND", okBody(sessionId, "M-SECOND", 0.9, "danger", 0.4))
                .andExpect(status().isConflict());

        assertThat(measurementMapper.findByMeasurementId("M-SECOND")).isEmpty();
        assertThat(measurementMapper.findByMeasurementId("M-FIRST").orElseThrow().getCargoHeight())
                .isEqualTo(0.5);
    }

    @Test
    void resendingSameMeasurementId_is409() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");
        postMeasurement("M-DUP", okBody(sessionId, "M-DUP", 0.5, "safe", 0.01)).andExpect(status().isCreated());
        postMeasurement("M-DUP", okBody(sessionId, "M-DUP", 0.9, "safe", 0.01)).andExpect(status().isConflict());

        assertThat(measurementMapper.findByMeasurementId("M-DUP").orElseThrow().getCargoHeight())
                .isEqualTo(0.5);
    }

    @Test
    void measurementWithoutActiveSession_is409() throws Exception {
        postMeasurement("M-NOSESSION", okBody("no-such-session", "M-NOSESSION", 0.5, "safe", 0.01))
                .andExpect(status().isConflict());

        assertThat(measurementMapper.findByMeasurementId("M-NOSESSION")).isEmpty();
    }

    // ── 늦은 측정 차단 (prompt107) ─────────────────────────────────────────

    @Test
    void lateMeasurementFromReleasedSession_isRejected_andNotStored() throws Exception {
        String sessionA = openSession("SSL-CARGO-1");
        // 측정 없이 강제 해제 — 데스크탑이 측정 전에 죽은 상황이다.
        mockMvc.perform(delete("/api/stations/sessions/{id}/force", sessionA))
                .andExpect(status().isOk());

        // 세션 A 가 뒤늦게 측정을 보낸다. 활성 세션이 없으므로 거부돼야 한다.
        postMeasurement("M-LATE-A", okBody(sessionA, "M-LATE-A", 0.723, "safe", 0.01))
                .andExpect(status().isConflict());

        assertThat(measurementMapper.findByMeasurementId("M-LATE-A")).isEmpty();
    }

    @Test
    void lateMeasurementIsNotAttributedToTheNewSession() throws Exception {
        String sessionA = openSession("SSL-CARGO-1");
        mockMvc.perform(delete("/api/stations/sessions/{id}/force", sessionA))
                .andExpect(status().isOk());

        // 새 세션 B 가 열린 뒤 세션 A 의 늦은 측정이 도착한다 — 이번 변경이 막으려는 바로 그 상황.
        String sessionB = openSession("SSL-CARGO-2");

        postMeasurement("M-LATE-A", okBody(sessionA, "M-LATE-A", 0.723, "safe", 0.01))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // 저장되지 않았고, 세션 B 가 오염되지도 않았다.
        assertThat(measurementMapper.findByMeasurementId("M-LATE-A")).isEmpty();
        assertThat(measurementMapper.existsBySessionId(sessionB)).isFalse();

        // 세션 B 자신의 측정은 정상 저장된다.
        postMeasurement("M-B", okBody(sessionB, "M-B", 0.5, "safe", 0.0))
                .andExpect(status().isCreated());
        assertThat(measurementMapper.findByMeasurementId("M-B").orElseThrow().getSessionId())
                .isEqualTo(sessionB);
    }

    @Test
    void measurementWithMissingSessionId_is400() throws Exception {
        openSession("SSL-CARGO-1");

        postMeasurement("M-NOSID", """
                {"measurementId":"M-NOSID","status":"ok","cargoHeight":0.723,
                 "tippingLevel":"safe","overhangRatio":0.01,"measuredAt":"2026-07-31T09:37:48+09:00"}
                """)
                .andExpect(status().isBadRequest());

        assertThat(measurementMapper.findByMeasurementId("M-NOSID")).isEmpty();
    }

    @Test
    void storedSessionIdEqualsRequestedSessionId() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        postMeasurement("M-SID", okBody(sessionId, "M-SID", 0.723, "safe", 0.01))
                .andExpect(status().isCreated());

        assertThat(measurementMapper.findByMeasurementId("M-SID").orElseThrow().getSessionId())
                .isEqualTo(sessionId);
    }

    // ── status 별: 저장·종료는 되고 추천만 막힌다 ───────────────────────────

    @Test
    void dimensionsOnly_isStoredAndClosesSession_butIsNotPlacementEligible() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        postMeasurement("M-DIM", """
                {"sessionId":"%s","measurementId":"M-DIM","status":"dimensions_only","cargoHeight":0.723,
                 "tippingLevel":null,"overhangRatio":null,"measuredAt":"2026-07-31T09:38:48+09:00"}
                """.formatted(sessionId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cargoHeight").value(0.723))
                .andExpect(jsonPath("$.data.tippingLevel").doesNotExist())
                .andExpect(jsonPath("$.data.overhangRatio").doesNotExist())
                // 높이가 있어도 전복·돌출 판정이 없으므로 추천 대상이 아니다.
                .andExpect(jsonPath("$.data.placementEligible").value(false));

        // 그래도 "측정 결과는 저장됨"이므로 세션은 닫을 수 있다.
        mockMvc.perform(delete("/api/stations/sessions/{id}", sessionId)).andExpect(status().isOk());
    }

    @Test
    void noDetectionAndUnreliable_areStoredAndCloseSession_butAreNotPlacementEligible() throws Exception {
        String first = openSession("SSL-CARGO-1");
        postMeasurement("M-ND", nullBody(first, "M-ND", "no_detection"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.placementEligible").value(false));
        mockMvc.perform(delete("/api/stations/sessions/{id}", first)).andExpect(status().isOk());

        String second = openSession("SSL-CARGO-2");
        postMeasurement("M-UN", nullBody(second, "M-UN", "unreliable"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.placementEligible").value(false));
        mockMvc.perform(delete("/api/stations/sessions/{id}", second)).andExpect(status().isOk());
    }

    @Test
    void okButUnsafe_isStoredAndClosesSession_butIsNotPlacementEligible() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        // WARNING 은 저장은 되지만 추천 대상이 아니다 — 저장 조건과 추천 조건은 별개다.
        postMeasurement("M-WARN", okBody(sessionId, "M-WARN", 0.723, "warning", 0.01))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.tippingLevel").value("WARNING"))
                .andExpect(jsonPath("$.data.placementEligible").value(false));

        mockMvc.perform(delete("/api/stations/sessions/{id}", sessionId)).andExpect(status().isOk());
    }

    @Test
    void okButOverhangAtLimit_isStored_butIsNotPlacementEligible() throws Exception {
        String sessionId = openSession("SSL-CARGO-1");

        // 0.05 는 경계 미포함이라 차단된다.
        postMeasurement("M-OVER", okBody(sessionId, "M-OVER", 0.723, "safe", 0.05))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.overhangRatio").value(0.05))
                .andExpect(jsonPath("$.data.placementEligible").value(false));
    }

    @Test
    void closingSessionThatIsNotActive_is409() throws Exception {
        openSession("SSL-CARGO-1");

        mockMvc.perform(delete("/api/stations/sessions/{id}", "no-such-session"))
                .andExpect(status().isConflict());
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private String openSession(String cargoId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/stations/sessions").param("cargoId", cargoId))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        // {"success":true,"data":{"sessionId":"...","cargoId":"..."},...}
        int idx = body.indexOf("\"sessionId\":\"") + "\"sessionId\":\"".length();
        return body.substring(idx, body.indexOf('"', idx));
    }

    private org.springframework.test.web.servlet.ResultActions postMeasurement(String id, String body)
            throws Exception {
        return mockMvc.perform(post("/api/stations/measurements")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String okBody(String sessionId, String measurementId, double cargoHeight,
            String tippingLevel, double overhang) {
        return """
                {"sessionId":"%s","measurementId":"%s","status":"ok","cargoHeight":%s,
                 "tippingLevel":"%s","overhangRatio":%s,"measuredAt":"2026-07-31T09:37:48+09:00"}
                """.formatted(sessionId, measurementId, cargoHeight, tippingLevel, overhang);
    }

    private String nullBody(String sessionId, String measurementId, String status) {
        return """
                {"sessionId":"%s","measurementId":"%s","status":"%s","cargoHeight":null,
                 "tippingLevel":null,"overhangRatio":null,"measuredAt":"2026-07-31T09:37:48+09:00"}
                """.formatted(sessionId, measurementId, status);
    }

    private void insertCargo(String cargoId) {
        Cargo cargo = new Cargo();
        cargo.setCargoId(cargoId);
        cargo.setWidth(1.0);
        cargo.setLength(1.0);
        cargo.setHeight(0.8);
        cargo.setVolume(0.8);
        cargo.setCreatedAt(LocalDateTime.now());
        cargo.setUpdatedAt(LocalDateTime.now());
        cargoMapper.insert(cargo);
    }
}
