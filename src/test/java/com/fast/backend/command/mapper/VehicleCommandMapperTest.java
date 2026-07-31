package com.fast.backend.command.mapper;

import com.fast.backend.command.domain.VehicleCommand;
import com.fast.backend.command.domain.VehicleCommandCategory;
import com.fast.backend.command.domain.VehicleCommandStatus;
import com.fast.backend.command.domain.VehicleCommandTargetSystem;
import com.fast.backend.command.domain.VehicleCommandType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 통합 명령 테이블의 신규 컬럼(target_system / command_category / payload_json)이 실제 H2(MySQL 호환
 * 모드)에서 왕복하는지 검증한다(prompt32.md 3장 3번·4번).
 *
 * <p>도메인 필드 {@code vehicleId}가 컬럼 {@code forklift_id}에 매핑되는 것도 여기서 실제 SQL 실행으로
 * 확인한다 — 이 매핑이 어긋나면 조회가 조용히 빈 결과를 돌려준다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleCommandMapperTest {

    @Autowired
    private VehicleCommandMapper commandMapper;

    @Test
    void insert_generatesIdAndRoundTripsAllConfirmedColumns() {
        VehicleCommand command = newCommand("CMD-M-001", "REAL-F01", VehicleCommandType.MOVE,
                VehicleCommandTargetSystem.ROS2, VehicleCommandCategory.MOVE);
        command.setPayloadJson("{\"destination\":{\"x\":5.0,\"y\":6.0,\"heading\":180.0,\"frameId\":\"map\"}}");

        commandMapper.insert(command);

        assertThat(command.getId()).isNotNull();
        VehicleCommand loaded = commandMapper.findByCommandId("CMD-M-001").orElseThrow();
        assertThat(loaded.getVehicleId()).isEqualTo("REAL-F01");
        assertThat(loaded.getCommand()).isEqualTo(VehicleCommandType.MOVE);
        assertThat(loaded.getTargetSystem()).isEqualTo(VehicleCommandTargetSystem.ROS2);
        assertThat(loaded.getCommandCategory()).isEqualTo(VehicleCommandCategory.MOVE);
        assertThat(loaded.getPayloadJson()).contains("\"frameId\":\"map\"");
        assertThat(loaded.getStatus()).isEqualTo(VehicleCommandStatus.PENDING);
    }

    @Test
    void insert_emergencyStopCombination_roundTrips() {
        commandMapper.insert(newCommand("CMD-M-002", "REAL-F01", VehicleCommandType.EMERGENCY_STOP,
                VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY));

        VehicleCommand loaded = commandMapper.findByCommandId("CMD-M-002").orElseThrow();
        assertThat(loaded.getTargetSystem()).isEqualTo(VehicleCommandTargetSystem.ALL);
        assertThat(loaded.getCommandCategory()).isEqualTo(VehicleCommandCategory.SAFETY);
    }

    @Test
    void insert_duplicateCommandId_violatesUniqueConstraint() {
        commandMapper.insert(newCommand("CMD-M-DUP", "REAL-F01", VehicleCommandType.STOP,
                VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.SAFETY));

        assertThatThrownBy(() -> commandMapper.insert(newCommand("CMD-M-DUP", "REAL-F01",
                VehicleCommandType.STOP, VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.SAFETY)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void update_writesResultFieldsIncludingEmergencyFlags() {
        VehicleCommand command = newCommand("CMD-M-003", "REAL-F01", VehicleCommandType.EMERGENCY_STOP,
                VehicleCommandTargetSystem.ALL, VehicleCommandCategory.SAFETY);
        commandMapper.insert(command);

        command.setStatus(VehicleCommandStatus.SUCCESS);
        command.setCompletedAt(LocalDateTime.now().withNano(0));
        command.setStoppedActions("DRIVE,STEERING,FORK");
        command.setEmergencyStopApplied(true);
        command.setRequiresReset(true);
        command.setResultMessage("주행과 포크 정지 완료");
        commandMapper.update(command);

        VehicleCommand loaded = commandMapper.findByCommandId("CMD-M-003").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(VehicleCommandStatus.SUCCESS);
        assertThat(loaded.getStoppedActions()).isEqualTo("DRIVE,STEERING,FORK");
        assertThat(loaded.getEmergencyStopApplied()).isTrue();
        assertThat(loaded.getRequiresReset()).isTrue();
        assertThat(loaded.getResultMessage()).isEqualTo("주행과 포크 정지 완료");
    }

    @Test
    void existsByCommandId_reflectsInsertedState() {
        assertThat(commandMapper.existsByCommandId("CMD-M-004")).isFalse();
        commandMapper.insert(newCommand("CMD-M-004", "REAL-F01", VehicleCommandType.LOAD,
                VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.LOAD));
        assertThat(commandMapper.existsByCommandId("CMD-M-004")).isTrue();
    }

    @Test
    void findRecentByVehicleId_ordersByIssuedAtDescendingAndSeparatesVehicles() {
        LocalDateTime base = LocalDateTime.now().withNano(0);
        insertAt("CMD-M-010", "REAL-F01", base.minusMinutes(2));
        insertAt("CMD-M-011", "REAL-F01", base.minusMinutes(1));
        insertAt("CMD-M-012", "SIM-F01", base);

        List<VehicleCommand> rows = commandMapper.findRecentByVehicleId("REAL-F01", 10, null);

        assertThat(rows).extracting(VehicleCommand::getCommandId)
                .containsExactly("CMD-M-011", "CMD-M-010");
    }

    @Test
    void findRecentByVehicleId_appliesLimit() {
        LocalDateTime base = LocalDateTime.now().withNano(0);
        insertAt("CMD-M-020", "REAL-F02", base.minusMinutes(3));
        insertAt("CMD-M-021", "REAL-F02", base.minusMinutes(2));
        insertAt("CMD-M-022", "REAL-F02", base.minusMinutes(1));

        assertThat(commandMapper.findRecentByVehicleId("REAL-F02", 2, null)).hasSize(2);
    }

    @Test
    void findByCommandId_unknownId_returnsEmpty() {
        Optional<VehicleCommand> loaded = commandMapper.findByCommandId("CMD-M-NONE");
        assertThat(loaded).isEmpty();
    }

    private void insertAt(String commandId, String vehicleId, LocalDateTime issuedAt) {
        VehicleCommand command = newCommand(commandId, vehicleId, VehicleCommandType.STOP,
                VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.SAFETY);
        command.setIssuedAt(issuedAt);
        commandMapper.insert(command);
    }

    private VehicleCommand newCommand(
            String commandId, String vehicleId, VehicleCommandType type,
            VehicleCommandTargetSystem targetSystem, VehicleCommandCategory category) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        VehicleCommand command = new VehicleCommand();
        command.setCommandId(commandId);
        command.setVehicleId(vehicleId);
        command.setCommand(type);
        command.setTargetSystem(targetSystem);
        command.setCommandCategory(category);
        command.setStatus(VehicleCommandStatus.PENDING);
        command.setIssuedAt(now);
        command.setCreatedAt(now);
        return command;
    }
}
