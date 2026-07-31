package com.fast.backend.station.controller;

<<<<<<< HEAD
import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementMessage.Detection;
import com.fast.backend.station.dto.StationMeasurementMessage.DetectedBox;
import com.fast.backend.station.dto.StationMeasurementMessage.Dimensions;
import com.fast.backend.station.dto.StationMeasurementMessage.Distance;
import com.fast.backend.station.dto.StationMeasurementMessage.LoadBalance;
import com.fast.backend.station.dto.StationMeasurementMessage.PalletDetection;
import com.fast.backend.station.service.StationMeasurementService;
=======
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.mapper.StationMeasurementMapper;
import com.fast.backend.station.service.StationMeasurementService;
import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.storage.mapper.CargoMapper;
import org.junit.jupiter.api.BeforeEach;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
<<<<<<< HEAD
=======
import org.springframework.http.MediaType;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

<<<<<<< HEAD
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
=======
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
<<<<<<< HEAD
 * 측정 스테이션 조회 REST API(prompt16.md 10·11단계 테스트 18·19)를 Controller → Service → Mapper → H2까지
 * 관통 검증한다(VehicleControllerIntegrationTest와 동일 스타일). 수신은 REST가 아니라 MQTT이므로
 * 저장은 Service.process를 직접 호출해 흉내낸다.
=======
 * {@code POST /api/stations/measurements} 종단 테스트(prompt95.md 23장) — 실제 Controller·Service·
 * MyBatis Mapper·H2 DB CHECK 제약을 모두 통과시켜 저장 결과를 확인한다.
 *
 * <p>DB CHECK({@code tipping_level IN ('SAFE','WARNING','DANGER')})가 실제로 걸려 있으므로, 소문자
 * 정규화가 빠지면 이 테스트는 INSERT 단계에서 깨진다 — 단위 테스트만으로는 잡히지 않는 지점이다.
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StationMeasurementControllerIntegrationTest {

<<<<<<< HEAD
    private static final OffsetDateTime MEASURED_AT =
            OffsetDateTime.of(2026, 7, 22, 13, 5, 1, 0, ZoneOffset.ofHours(9));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StationMeasurementService service;

    @Test
    void getByMeasurementId_returnsStoredMeasurement() throws Exception {
        service.process(okMessage("CTRL-01", "station-1"));

        mockMvc.perform(get("/api/stations/measurements/CTRL-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.measurementId").value("CTRL-01"))
                .andExpect(jsonPath("$.data.stationId").value("station-1"))
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.data.measuredAt").value("2026-07-22T13:05:01+09:00"))
                .andExpect(jsonPath("$.data.detection.pallet.score").value(0.99))
                .andExpect(jsonPath("$.data.loadBalance.direction[0]").value("right"));
    }

    @Test
    void getLatestByStationId_returnsMostRecent() throws Exception {
        service.process(okMessage("CTRL-02a", "station-latest"));
        service.process(laterMessage("CTRL-02b", "station-latest"));

        mockMvc.perform(get("/api/stations/station-latest/measurements/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.measurementId").value("CTRL-02b"));
    }

    @Test
    void getByMeasurementId_unknown_returns404() throws Exception {
        mockMvc.perform(get("/api/stations/measurements/NO-SUCH-MEASUREMENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STATION_MEASUREMENT_NOT_FOUND"));
    }

    @Test
    void getLatestByStationId_noMeasurement_returns404() throws Exception {
        mockMvc.perform(get("/api/stations/no-such-station/measurements/latest"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("STATION_MEASUREMENT_NOT_FOUND"));
    }

    private StationMeasurementMessage okMessage(String measurementId, String stationId) {
        return message(measurementId, stationId, MEASURED_AT);
    }

    private StationMeasurementMessage laterMessage(String measurementId, String stationId) {
        return message(measurementId, stationId, MEASURED_AT.plusMinutes(5));
    }

    private StationMeasurementMessage message(String measurementId, String stationId, OffsetDateTime measuredAt) {
        Detection detection = new Detection(1,
                List.of(new DetectedBox(List.of(412, 180, 350, 310), 0.97)),
                new PalletDetection(List.of(380, 460, 520, 140), 0.99));
        return new StationMeasurementMessage("1.0", measurementId, stationId, measuredAt, "ok",
                detection,
                new Distance(152.3, 0.42, 48),
                new Dimensions(30.2, 34.1, null, 10, 30.2, 34.1),
                new LoadBalance(true, List.of("right"), 0.40, 0.02, 0.40, 0.3, "오른쪽 편하중"));
=======
    @Autowired private MockMvc mockMvc;
    @Autowired private CargoMapper cargoMapper;
    @Autowired private StationMeasurementMapper measurementMapper;
    @Autowired private StationMeasurementService stationMeasurementService;

    private String activeSessionId;

    @BeforeEach
    void openSession() {
        Cargo cargo = new Cargo();
        cargo.setCargoId("SMC-CARGO-1");
        cargo.setCreatedAt(LocalDateTime.now());
        cargoMapper.insert(cargo);
        activeSessionId = stationMeasurementService.openSession("SMC-CARGO-1").getSessionId();
    }

    @Test
    void postMeasurement_ok_storesMeterHeightUppercaseLevelAndActiveSession() throws Exception {
        mockMvc.perform(post("/api/stations/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "measurementId": "M-001",
                                  "status": "ok",
                                  "cargoHeight": 0.723,
                                  "tippingLevel": "safe",
                                  "overhangRatio": 0.057,
                                  "measuredAt": "2026-07-31T09:37:48+09:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.measurementId").value("M-001"))
                .andExpect(jsonPath("$.data.sessionId").value(activeSessionId))
                .andExpect(jsonPath("$.data.cargoId").value("SMC-CARGO-1"))
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.data.tippingLevel").value("SAFE"));

        StationMeasurement saved = findStored("M-001");
        assertThat(saved.getCargoHeight()).isEqualTo(0.723);
        assertThat(saved.getTippingLevel()).isEqualTo("SAFE");
        assertThat(saved.getOverhangRatio()).isEqualTo(0.057);
        assertThat(saved.getSessionId()).isEqualTo(activeSessionId);
    }

    @Test
    void postMeasurement_dimensionsOnly_storesHeightWithNullRiskValues() throws Exception {
        mockMvc.perform(post("/api/stations/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "measurementId": "M-002",
                                  "status": "dimensions_only",
                                  "cargoHeight": 0.723,
                                  "tippingLevel": null,
                                  "overhangRatio": null,
                                  "measuredAt": "2026-07-31T09:38:48+09:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("dimensions_only"));

        StationMeasurement saved = findStored("M-002");
        assertThat(saved.getCargoHeight()).isEqualTo(0.723);
        assertThat(saved.getTippingLevel()).isNull();
        assertThat(saved.getOverhangRatio()).isNull();
    }

    @Test
    void postMeasurement_duplicateMeasurementId_returns409AndKeepsOriginal() throws Exception {
        String body = """
                {
                  "measurementId": "M-DUP",
                  "status": "ok",
                  "cargoHeight": %s,
                  "tippingLevel": "safe",
                  "overhangRatio": 0.01,
                  "measuredAt": "2026-07-31T09:39:48+09:00"
                }
                """;

        mockMvc.perform(post("/api/stations/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("0.5")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/stations/measurements")
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("0.9")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // 재전송이 기존 값을 덮어쓰지 않았는지 — 갱신도 새 행 추가도 하지 않는다.
        assertThat(findStored("M-DUP").getCargoHeight()).isEqualTo(0.5);
    }

    @Test
    void postMeasurement_invalidTippingLevel_returns400AndStoresNothing() throws Exception {
        mockMvc.perform(post("/api/stations/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "measurementId": "M-BAD",
                                  "status": "ok",
                                  "cargoHeight": 0.723,
                                  "tippingLevel": "critical",
                                  "overhangRatio": 0.0,
                                  "measuredAt": "2026-07-31T09:40:48+09:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(measurementMapper.findByMeasurementId("M-BAD")).isEmpty();
    }

    @Test
    void postMeasurement_withoutActiveSession_returns409() throws Exception {
        stationMeasurementService.closeSession(activeSessionId);

        mockMvc.perform(post("/api/stations/measurements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "measurementId": "M-NOSESSION",
                                  "status": "ok",
                                  "cargoHeight": 0.723,
                                  "tippingLevel": "safe",
                                  "overhangRatio": 0.0,
                                  "measuredAt": "2026-07-31T09:41:48+09:00"
                                }
                                """))
                .andExpect(status().isConflict());

        assertThat(measurementMapper.findByMeasurementId("M-NOSESSION")).isEmpty();
    }

    private StationMeasurement findStored(String measurementId) {
        Optional<StationMeasurement> found = measurementMapper.findByMeasurementId(measurementId);
        assertThat(found).as("measurementId=%s 가 저장돼 있어야 한다", measurementId).isPresent();
        return found.get();
>>>>>>> ad35a6d (feat: 스테이션 계측 REST API)
    }
}
