package com.fast.backend.loadsafety.controller;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 적재 안전 조회 REST 계층 종단 테스트(prompt70.md 7장).
 *
 * <p>서비스 계층은 {@code LoadSafetyServiceIntegrationTest}가 이미 검증하므로 여기서는 <b>REST 계약만</b>
 * 확인한다 — 특히 <b>데이터가 없을 때의 응답</b>이다. GPU 서버 최초 기동 시에는 적재 안전 발행 주체가
 * 아직 없어 이 "빈 상태"가 정상 응답이며, 이것이 200으로 안전하게 내려오지 않으면 배포 직후 화면이
 * 오류로 보인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoadSafetyControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private VehicleMapper vehicleMapper;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void latestAll_withNoData_returnsEmptyListNotError() throws Exception {
        // 배포 직후 상태: 적재 안전 메시지를 한 건도 받지 않았다.
        mockMvc.perform(get("/api/vehicles/load-safety/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void latestByVehicle_neverReceived_returns200WithNullData() throws Exception {
        register("LSC-F01");

        // 미수신은 오류가 아니다 — /location/latest와 동일하게 200 + data:null 이어야 한다.
        // 404로 바뀌면 프론트가 "등록되지 않은 차량"과 구분하지 못한다.
        mockMvc.perform(get("/api/vehicles/{vehicleId}/load-safety/latest", "LSC-F01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void latestByVehicle_unregisteredVehicle_returns404() throws Exception {
        // 오타 난 vehicleId에 "데이터 없음"을 돌려주면 호출자가 차량이 조용한 것인지
        // 존재하지 않는 것인지 구분할 수 없다.
        mockMvc.perform(get("/api/vehicles/{vehicleId}/load-safety/latest", "LSC-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VEHICLE_NOT_FOUND"));
    }

    private void register(String vehicleId) {
        Vehicle v = new Vehicle();
        v.setVehicleId(vehicleId);
        v.setName(vehicleId);
        v.setSource(VehicleSource.REAL);
        v.setActive(true);
        v.setCreatedAt(NOW);
        v.setUpdatedAt(NOW);
        vehicleMapper.insert(v);
    }
}
