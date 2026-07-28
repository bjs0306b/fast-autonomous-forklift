package com.fast.backend.command.service;

import com.fast.backend.command.dto.VehicleCommandDestination;
import com.fast.backend.command.dto.VehicleCommandRequest;
import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.common.exception.BusinessException;
import com.fast.backend.common.exception.ErrorCode;
import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleSource;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;

/**
 * 최근 <b>안전</b> 명령 조회 검증(prompt56.md 12장 A안, 16장 35~38번).
 *
 * <p>필터 없이 {@code limit=1}로 조회하면 직전 MOVE 명령이 "최근 안전 명령"으로 잘못 보인다.
 * {@code category=SAFETY}를 주면 STOP/EMERGENCY_STOP만 걸러지는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SafetyCommandCategoryQueryIntegrationTest {

    @Autowired private VehicleCommandService vehicleCommandService;
    @Autowired private SafetyCommandService safetyCommandService;
    @Autowired private VehicleMapper vehicleMapper;

    @MockBean private VehicleCommandPublisher publisher;

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void latestSafetyCommand_isFoundEvenWhenNewerMoveCommandExists() {
        doNothing().when(publisher).publish(any());
        register("SCQ1-F01");

        safetyCommandService.stop("SCQ1-F01", null);                 // SAFETY
        issueMove("SCQ1-F01");                                        // 이후 MOVE (가장 최신)

        // 필터 없음 → MOVE가 최신이라 안전 명령 표시에 부적합
        List<VehicleCommandResponse> unfiltered =
                vehicleCommandService.findRecentByVehicleId("SCQ1-F01", 1);
        assertThat(unfiltered).singleElement()
                .satisfies(c -> assertThat(c.command()).isEqualTo("MOVE"));

        // category=SAFETY → MOVE를 건너뛰고 STOP을 돌려준다
        List<VehicleCommandResponse> safety =
                vehicleCommandService.findRecentByVehicleId("SCQ1-F01", 1, "SAFETY");
        assertThat(safety).singleElement().satisfies(c -> {
            assertThat(c.command()).isEqualTo("STOP");
            assertThat(c.commandCategory()).isEqualTo("SAFETY");
        });
    }

    @Test
    void latestSafetyCommand_returnsEmergencyStopWhenItIsTheNewestSafetyCommand() {
        doNothing().when(publisher).publish(any());
        register("SCQ2-F01");

        safetyCommandService.stop("SCQ2-F01", null);
        safetyCommandService.emergencyStop("SCQ2-F01", null);
        issueMove("SCQ2-F01");

        List<VehicleCommandResponse> safety =
                vehicleCommandService.findRecentByVehicleId("SCQ2-F01", 1, "SAFETY");

        assertThat(safety).singleElement()
                .satisfies(c -> assertThat(c.command()).isEqualTo("EMERGENCY_STOP"));
    }

    @Test
    void latestSafetyCommand_returnsEmptyListWhenNoSafetyCommandExists() {
        doNothing().when(publisher).publish(any());
        register("SCQ3-F01");
        issueMove("SCQ3-F01");

        // 안전 명령이 없으면 예외가 아니라 빈 목록 — 기존 목록 API의 빈 응답 정책 그대로.
        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ3-F01", 1, "SAFETY")).isEmpty();
    }

    @Test
    void categoryFilter_isOptional_andKeepsExistingBehaviourWhenAbsent() {
        doNothing().when(publisher).publish(any());
        register("SCQ4-F01");
        safetyCommandService.stop("SCQ4-F01", null);
        issueMove("SCQ4-F01");

        // null/빈 문자열은 "필터 없음" — 기존 호출과 동일하게 전체 분류를 반환한다.
        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ4-F01", 50, null)).hasSize(2);
        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ4-F01", 50, "  ")).hasSize(2);
        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ4-F01", 50)).hasSize(2);
    }

    @Test
    void categoryFilter_acceptsLowerCaseAndRejectsUnknownValue() {
        doNothing().when(publisher).publish(any());
        register("SCQ5-F01");
        safetyCommandService.stop("SCQ5-F01", null);

        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ5-F01", 1, "safety")).hasSize(1);

        // 오타를 조용히 무시하고 전체를 반환하면 호출자가 오해하므로 400으로 거부한다.
        assertThatThrownBy(() -> vehicleCommandService.findRecentByVehicleId("SCQ5-F01", 1, "SAFTY"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.COMMAND_COMBINATION_INVALID);
    }

    @Test
    void categoryFilter_separatesMoveAndSafetyCategories() {
        doNothing().when(publisher).publish(any());
        register("SCQ6-F01");
        safetyCommandService.stop("SCQ6-F01", null);
        issueMove("SCQ6-F01");

        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ6-F01", 50, "MOVE"))
                .singleElement().satisfies(c -> assertThat(c.command()).isEqualTo("MOVE"));
        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ6-F01", 50, "SAFETY"))
                .singleElement().satisfies(c -> assertThat(c.command()).isEqualTo("STOP"));
        assertThat(vehicleCommandService.findRecentByVehicleId("SCQ6-F01", 50, "FORK")).isEmpty();
    }

    // --- helpers ---

    private void issueMove(String vehicleId) {
        vehicleCommandService.issueCommand(vehicleId, new VehicleCommandRequest(
                "MOVE", null, null, new VehicleCommandDestination(1.0, 2.0, 90.0, "map"), null));
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
