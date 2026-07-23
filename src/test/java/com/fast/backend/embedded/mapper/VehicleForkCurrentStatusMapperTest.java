package com.fast.backend.embedded.mapper;

import com.fast.backend.embedded.domain.EmbeddedForkState;
import com.fast.backend.embedded.domain.VehicleForkCurrentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * vehicle_fork_current_status의 INSERT ... ON DUPLICATE KEY UPDATE(upsert) 동작이 실제 H2(MySQL 호환
 * 모드)에서 신규 삽입·기존 행 갱신 모두 올바른지 검증한다(prompt29.md 17장, vehicle_current_status.upsert()
 * 와 동일 패턴).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleForkCurrentStatusMapperTest {

    @Autowired
    private VehicleForkCurrentStatusMapper mapper;

    @Test
    void upsert_newForkliftId_insertsRow() {
        VehicleForkCurrentStatus status = newStatus("REAL01", EmbeddedForkState.STOPPED, false);

        mapper.upsert(status);

        VehicleForkCurrentStatus found = mapper.findByForkliftId("REAL01").orElseThrow();
        assertThat(found.getForkState()).isEqualTo(EmbeddedForkState.STOPPED);
        assertThat(found.getLimitBottom()).isFalse();
    }

    @Test
    void upsert_existingForkliftId_updatesInPlaceWithoutDuplicateRow() {
        mapper.upsert(newStatus("REAL02", EmbeddedForkState.MOVING_UP, false));

        mapper.upsert(newStatus("REAL02", EmbeddedForkState.BOTTOM, true));

        VehicleForkCurrentStatus found = mapper.findByForkliftId("REAL02").orElseThrow();
        assertThat(found.getForkState()).isEqualTo(EmbeddedForkState.BOTTOM);
        assertThat(found.getLimitBottom()).isTrue();
    }

    @Test
    void findByForkliftId_unknownForkliftId_returnsEmpty() {
        Optional<VehicleForkCurrentStatus> found = mapper.findByForkliftId("NO-SUCH");

        assertThat(found).isEmpty();
    }

    private VehicleForkCurrentStatus newStatus(String forkliftId, EmbeddedForkState state, boolean limitBottom) {
        LocalDateTime now = LocalDateTime.now();
        VehicleForkCurrentStatus status = new VehicleForkCurrentStatus();
        status.setForkliftId(forkliftId);
        status.setForkState(state);
        status.setLimitBottom(limitBottom);
        status.setErrorCode(null);
        status.setMessageAt(now);
        status.setReceivedAt(now);
        status.setUpdatedAt(now);
        return status;
    }
}
