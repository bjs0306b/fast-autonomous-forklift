package com.fast.backend.station.controller;

import com.fast.backend.station.dto.StationMeasurementMessage;
import com.fast.backend.station.dto.StationMeasurementMessage.Detection;
import com.fast.backend.station.dto.StationMeasurementMessage.DetectedBox;
import com.fast.backend.station.dto.StationMeasurementMessage.Dimensions;
import com.fast.backend.station.dto.StationMeasurementMessage.Distance;
import com.fast.backend.station.dto.StationMeasurementMessage.LoadBalance;
import com.fast.backend.station.dto.StationMeasurementMessage.PalletDetection;
import com.fast.backend.station.service.StationMeasurementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 측정 스테이션 조회 REST API(prompt16.md 10·11단계 테스트 18·19)를 Controller → Service → Mapper → H2까지
 * 관통 검증한다(VehicleControllerIntegrationTest와 동일 스타일). 수신은 REST가 아니라 MQTT이므로
 * 저장은 Service.process를 직접 호출해 흉내낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StationMeasurementControllerIntegrationTest {

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
    }
}
