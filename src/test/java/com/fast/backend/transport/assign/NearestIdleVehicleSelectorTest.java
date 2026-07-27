package com.fast.backend.transport.assign;

import com.fast.backend.vehicle.domain.VehicleStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동 배정 초안 검증(prompt46.md 19장 21~24번, 12장). 위치가 있는 후보에 한해 동작한다.
 */
class NearestIdleVehicleSelectorTest {

    private final NearestIdleVehicleSelector selector = new NearestIdleVehicleSelector();

    private static VehicleCandidate vehicle(
            String id, boolean online, VehicleStatus status, boolean active, Double x, Double y) {
        return new VehicleCandidate(id, online, status, active, x, y,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void selectsNearestIdleVehicle() {
        VehicleCandidate near = vehicle("REAL-F02", true, VehicleStatus.IDLE, false, 1.0, 0.0);
        VehicleCandidate far = vehicle("REAL-F01", true, VehicleStatus.IDLE, false, 9.0, 0.0);
        Optional<VehicleCandidate> selected = selector.select(0.0, 0.0, List.of(far, near));
        assertThat(selected).isPresent();
        assertThat(selected.get().vehicleId()).isEqualTo("REAL-F02");
    }

    @Test
    void excludesVehicleWithActiveTask() {
        VehicleCandidate busy = vehicle("REAL-F01", true, VehicleStatus.IDLE, true, 1.0, 0.0);
        assertThat(selector.select(0.0, 0.0, List.of(busy))).isEmpty();
    }

    @Test
    void excludesErrorAndOfflineVehicles() {
        VehicleCandidate error = vehicle("REAL-F01", true, VehicleStatus.ERROR, false, 1.0, 0.0);
        VehicleCandidate offline = vehicle("REAL-F02", false, VehicleStatus.IDLE, false, 1.0, 0.0);
        assertThat(selector.select(0.0, 0.0, List.of(error, offline))).isEmpty();
    }

    @Test
    void returnsEmptyWhenNoCandidate() {
        assertThat(selector.select(0.0, 0.0, List.of())).isEmpty();
    }

    @Test
    void excludesCandidateWithoutPosition() {
        // 위치 미저장(prompt44) 상황: positionX/Y가 null이면 거리 계산 불가라 제외된다.
        VehicleCandidate noPosition = vehicle("REAL-F01", true, VehicleStatus.IDLE, false, null, null);
        assertThat(selector.select(0.0, 0.0, List.of(noPosition))).isEmpty();
    }
}
