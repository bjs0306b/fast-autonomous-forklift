package com.fast.backend.embedded.mapper;

import com.fast.backend.embedded.domain.EmbeddedCommandStatus;
import com.fast.backend.embedded.domain.EmbeddedCommandType;
import com.fast.backend.embedded.domain.EmbeddedVehicleCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * embedded_vehicle_command insert/update/조회, command_id UNIQUE 제약이 실제 H2(MySQL 호환 모드)에서
 * 동작하는지 검증한다(prompt29.md 17장·22장).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmbeddedVehicleCommandMapperTest {

    @Autowired
    private EmbeddedVehicleCommandMapper mapper;

    @Test
    void insert_generatesId() {
        EmbeddedVehicleCommand command = newCommand("CMD-01", "REAL01");

        mapper.insert(command);

        assertThat(command.getId()).isNotNull();
    }

    @Test
    void findByCommandId_returnsInsertedRow() {
        mapper.insert(newCommand("CMD-02", "REAL01"));

        Optional<EmbeddedVehicleCommand> found = mapper.findByCommandId("CMD-02");

        assertThat(found).isPresent();
        assertThat(found.get().getForkliftId()).isEqualTo("REAL01");
        assertThat(found.get().getCommand()).isEqualTo(EmbeddedCommandType.FORK_UP);
        assertThat(found.get().getStatus()).isEqualTo(EmbeddedCommandStatus.PENDING);
    }

    @Test
    void findByCommandId_unknownId_returnsEmpty() {
        assertThat(mapper.findByCommandId("NO-SUCH")).isEmpty();
    }

    @Test
    void existsByCommandId_detectsExistence() {
        mapper.insert(newCommand("CMD-03", "REAL01"));

        assertThat(mapper.existsByCommandId("CMD-03")).isTrue();
        assertThat(mapper.existsByCommandId("NO-SUCH")).isFalse();
    }

    @Test
    void insert_duplicateCommandId_violatesDbUniqueConstraint() {
        mapper.insert(newCommand("CMD-DUP", "REAL01"));

        assertThatThrownBy(() -> mapper.insert(newCommand("CMD-DUP", "REAL01")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_changesStatusAndResultFields() {
        EmbeddedVehicleCommand command = newCommand("CMD-04", "REAL01");
        mapper.insert(command);

        command.setStatus(EmbeddedCommandStatus.SUCCESS);
        command.setCompletedAt(LocalDateTime.now());
        command.setErrorCode(null);
        command.setResultMessage("완료");
        command.setStoppedActions(null);
        command.setEmergencyStopApplied(false);
        command.setRequiresReset(false);
        command.setUpdatedAt(LocalDateTime.now());
        mapper.update(command);

        EmbeddedVehicleCommand updated = mapper.findByCommandId("CMD-04").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(EmbeddedCommandStatus.SUCCESS);
        assertThat(updated.getResultMessage()).isEqualTo("완료");
    }

    @Test
    void update_savesEmergencyStopFieldsAndStoppedActionsCsv() {
        EmbeddedVehicleCommand command = newCommand("CMD-05", "REAL01");
        command.setCommand(EmbeddedCommandType.EMERGENCY_STOP);
        mapper.insert(command);

        command.setStatus(EmbeddedCommandStatus.SUCCESS);
        command.setStoppedActions("DRIVE,STEERING,FORK");
        command.setEmergencyStopApplied(true);
        command.setRequiresReset(true);
        command.setUpdatedAt(LocalDateTime.now());
        mapper.update(command);

        EmbeddedVehicleCommand updated = mapper.findByCommandId("CMD-05").orElseThrow();
        assertThat(updated.getStoppedActions()).isEqualTo("DRIVE,STEERING,FORK");
        assertThat(updated.getEmergencyStopApplied()).isTrue();
        assertThat(updated.getRequiresReset()).isTrue();
    }

    @Test
    void findRecentByForkliftId_ordersByIssuedAtDescendingAndRespectsLimit() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        for (int i = 0; i < 3; i++) {
            EmbeddedVehicleCommand command = newCommand("CMD-RECENT-" + i, "REAL02");
            command.setIssuedAt(base.plusMinutes(i));
            command.setCreatedAt(base.plusMinutes(i));
            command.setUpdatedAt(base.plusMinutes(i));
            mapper.insert(command);
        }

        List<EmbeddedVehicleCommand> recent = mapper.findRecentByForkliftId("REAL02", 2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getCommandId()).isEqualTo("CMD-RECENT-2");
        assertThat(recent.get(1).getCommandId()).isEqualTo("CMD-RECENT-1");
    }

    @Test
    void findRecentByForkliftId_separatesDifferentForklifts() {
        mapper.insert(newCommand("CMD-A1", "REAL03"));
        mapper.insert(newCommand("CMD-B1", "REAL04"));

        List<EmbeddedVehicleCommand> recent = mapper.findRecentByForkliftId("REAL03", 50);

        assertThat(recent).extracting(EmbeddedVehicleCommand::getCommandId).containsExactly("CMD-A1");
    }

    private EmbeddedVehicleCommand newCommand(String commandId, String forkliftId) {
        LocalDateTime now = LocalDateTime.now();
        EmbeddedVehicleCommand command = new EmbeddedVehicleCommand();
        command.setCommandId(commandId);
        command.setForkliftId(forkliftId);
        command.setCommand(EmbeddedCommandType.FORK_UP);
        command.setReason("테스트");
        command.setStatus(EmbeddedCommandStatus.PENDING);
        command.setIssuedAt(now);
        command.setCreatedAt(now);
        command.setUpdatedAt(now);
        return command;
    }
}
