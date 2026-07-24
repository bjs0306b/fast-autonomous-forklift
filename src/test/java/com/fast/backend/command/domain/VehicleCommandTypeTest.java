package com.fast.backend.command.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정 명령 조합표(prompt32.md 1장 8번)를 코드로 고정한다. 이 표가 흔들리면 수신 측(ROS2 브리지/임베디드
 * 펌웨어)의 1차 분기가 통째로 무너지므로, 조합 하나하나를 명시적으로 검증한다.
 */
class VehicleCommandTypeTest {

    @Test
    void fromRaw_knownValues_areParsedCaseInsensitively() {
        assertThat(VehicleCommandType.fromRaw("MOVE")).contains(VehicleCommandType.MOVE);
        assertThat(VehicleCommandType.fromRaw("fork_up")).contains(VehicleCommandType.FORK_UP);
        assertThat(VehicleCommandType.fromRaw("  EMERGENCY_STOP  ")).contains(VehicleCommandType.EMERGENCY_STOP);
    }

    @Test
    void fromRaw_unknownOrBlank_returnsEmptyAndNeverFallsBack() {
        // 안전 명령을 다루는 도메인이라 "모르는 값을 적당히 해석"하지 않는다.
        assertThat(VehicleCommandType.fromRaw("LIFT")).isEmpty();
        assertThat(VehicleCommandType.fromRaw(null)).isEmpty();
        assertThat(VehicleCommandType.fromRaw("  ")).isEmpty();
    }

    @Test
    void move_requiresRos2AndMoveCategoryAndDestination() {
        assertThat(VehicleCommandType.MOVE.requiredTargetSystem()).isEqualTo(VehicleCommandTargetSystem.ROS2);
        assertThat(VehicleCommandType.MOVE.requiredCategory()).isEqualTo(VehicleCommandCategory.MOVE);
        assertThat(VehicleCommandType.MOVE.isDestinationRequired()).isTrue();
    }

    @Test
    void forkCommands_requireEmbeddedAndForkCategory() {
        for (VehicleCommandType type : new VehicleCommandType[]{VehicleCommandType.FORK_UP, VehicleCommandType.FORK_DOWN}) {
            assertThat(type.requiredTargetSystem()).isEqualTo(VehicleCommandTargetSystem.EMBEDDED);
            assertThat(type.requiredCategory()).isEqualTo(VehicleCommandCategory.FORK);
            assertThat(type.isDestinationRequired()).isFalse();
        }
    }

    @Test
    void loadCommands_requireEmbeddedAndLoadCategory() {
        for (VehicleCommandType type : new VehicleCommandType[]{VehicleCommandType.LOAD, VehicleCommandType.UNLOAD}) {
            assertThat(type.requiredTargetSystem()).isEqualTo(VehicleCommandTargetSystem.EMBEDDED);
            assertThat(type.requiredCategory()).isEqualTo(VehicleCommandCategory.LOAD);
        }
    }

    @Test
    void emergencyCommands_requireAllTargetsAndSafetyCategory() {
        // 확정 규격의 핵심: 수신 측이 payload를 깊게 파싱하기 전에 ALL+SAFETY 조합만 보고 비상 명령임을
        // 알 수 있어야 한다.
        for (VehicleCommandType type : new VehicleCommandType[]{
                VehicleCommandType.EMERGENCY_STOP, VehicleCommandType.RESET_ESTOP}) {
            assertThat(type.requiredTargetSystem()).isEqualTo(VehicleCommandTargetSystem.ALL);
            assertThat(type.requiredCategory()).isEqualTo(VehicleCommandCategory.SAFETY);
            assertThat(type.isEmergency()).isTrue();
        }
    }

    @Test
    void nonEmergencyCommands_areNotFlaggedAsEmergency() {
        assertThat(VehicleCommandType.MOVE.isEmergency()).isFalse();
        assertThat(VehicleCommandType.FORK_UP.isEmergency()).isFalse();
        assertThat(VehicleCommandType.STOP.isEmergency()).isFalse();
    }

    @Test
    void stop_isEmbeddedSafety_asDocumented() {
        // 확정 규격의 조합표에 STOP 항목이 없어 기존 동작(실물 임베디드 명령)을 유지하기로 결정했다.
        // 이 조합은 팀 확인이 필요한 항목이며 문서에도 그렇게 표시돼 있다.
        assertThat(VehicleCommandType.STOP.requiredTargetSystem()).isEqualTo(VehicleCommandTargetSystem.EMBEDDED);
        assertThat(VehicleCommandType.STOP.requiredCategory()).isEqualTo(VehicleCommandCategory.SAFETY);
    }

    @Test
    void matches_returnsTrueOnlyForTheConfirmedCombination() {
        assertThat(VehicleCommandType.MOVE.matches(VehicleCommandTargetSystem.ROS2, VehicleCommandCategory.MOVE)).isTrue();
        assertThat(VehicleCommandType.MOVE.matches(VehicleCommandTargetSystem.EMBEDDED, VehicleCommandCategory.MOVE)).isFalse();
        assertThat(VehicleCommandType.MOVE.matches(VehicleCommandTargetSystem.ROS2, VehicleCommandCategory.SAFETY)).isFalse();
    }

    @Test
    void everyCommand_hasAConsistentSelfDescribedCombination() {
        for (VehicleCommandType type : VehicleCommandType.values()) {
            assertThat(type.matches(type.requiredTargetSystem(), type.requiredCategory())).isTrue();
        }
    }
}
