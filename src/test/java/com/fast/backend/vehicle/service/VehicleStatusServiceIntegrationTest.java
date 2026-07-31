package com.fast.backend.vehicle.service;

import com.fast.backend.vehicle.domain.Vehicle;
import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.domain.VehicleStatusHistory;
import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;
import com.fast.backend.vehicle.mapper.VehicleCurrentStatusMapper;
import com.fast.backend.vehicle.mapper.VehicleMapper;
import com.fast.backend.vehicle.mapper.VehicleStatusHistoryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VehicleStatusService#updateCurrentStatus}의 상태 저장 흐름을 실제 Spring 컨텍스트 + 임베디드
 * H2 + MyBatis Mapper로 관통해 검증한다(prompt14.md 작업 A). Mockito를 전혀 쓰지 않으므로 JDK/Mockito
 * 호환성과 무관하게 항상 실행된다 — 그동안 이 저장 오케스트레이션(upsert→이력 insert→stale/중복 정책)을
 * 실행으로 증명하던 유일한 테스트가 Mockito 기반 {@code VehicleStatusServiceTest}뿐이라, 현재 JDK
 * 환경에서 실행되지 못하던 공백(answer13.md P1)을 이 테스트가 메운다.
 *
 * <p>클래스 레벨 {@code @Transactional}로 각 테스트 후 롤백해 테스트 간 독립성을 유지한다
 * (기존 {@code VehicleStatusHistoryMapperTest}/{@code VehicleControllerIntegrationTest}와 동일 방식).
 * 이력 insert 실패 시 롤백(테스트 5)만은 실제 커밋 경계가 필요해 별도 클래스
 * {@link VehicleStatusServiceRollbackIntegrationTest}로 분리했다(그 클래스 Javadoc 참고).
 *
 * <p>messageAt은 모두 {@code withNano(0)}(초 단위)으로 만든다 — H2/MySQL DATETIME 컬럼이 초 미만
 * 정밀도를 절삭해, DB에서 다시 읽은 기존 messageAt과 in-memory 값의 비교(stale 판정)가 나노초 차이로
 * 흔들리는 것을 막기 위함이다. 실제 메시지 timestamp도 이 수준의 정밀도를 넘지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VehicleStatusServiceIntegrationTest {

    @Autowired
    private VehicleStatusService vehicleStatusService;

    @Autowired
    private VehicleMapper vehicleMapper;

    @Autowired
    private VehicleCurrentStatusMapper vehicleCurrentStatusMapper;

    @Autowired
    private VehicleStatusHistoryMapper vehicleStatusHistoryMapper;

    /** 테스트 1: 최초 상태 메시지 → current_status 1건 upsert + status_history 1건 insert, 값 일치. */
    @Test
    void updateCurrentStatus_firstMessage_upsertsCurrentAndInsertsHistory() {
        insertVehicle("ISVC-01");
        LocalDateTime t1 = LocalDateTime.now().withNano(0);

        vehicleStatusService.updateCurrentStatus("ISVC-01",
                new VehicleStatusUpdateCommand("ACTIVE", 88, 1.5, 2.5, 90.0, 0.7, t1.atOffset(java.time.ZoneOffset.ofHours(9))));

        Optional<VehicleCurrentStatus> current = vehicleCurrentStatusMapper.findByVehicleId("ISVC-01");
        assertThat(current).isPresent();
        VehicleCurrentStatus cur = current.get();
        assertThat(cur.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(cur.getBattery()).isEqualTo(88);
        assertThat(cur.getPositionX()).isEqualTo(1.5);
        assertThat(cur.getPositionY()).isEqualTo(2.5);
        assertThat(cur.getHeading()).isEqualTo(90.0);
        assertThat(cur.getSpeed()).isEqualTo(0.7);
        assertThat(cur.getMessageAt()).isEqualTo(t1);

        List<VehicleStatusHistory> history = vehicleStatusHistoryMapper.findRecentByVehicleId("ISVC-01", 10);
        assertThat(history).hasSize(1);
        VehicleStatusHistory h = history.get(0);
        assertThat(h.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(h.getBattery()).isEqualTo(88);
        assertThat(h.getPositionX()).isEqualTo(1.5);
        assertThat(h.getPositionY()).isEqualTo(2.5);
        assertThat(h.getHeading()).isEqualTo(90.0);
        assertThat(h.getSpeed()).isEqualTo(0.7);
        assertThat(h.getMessageAt()).isEqualTo(t1);
        // 현재 상태와 이력이 같은 메시지 값을 반영하는지
        assertThat(h.getStatus()).isEqualTo(cur.getStatus());
        assertThat(h.getMessageAt()).isEqualTo(cur.getMessageAt());
    }

    /** 테스트 2: 더 늦은 messageAt의 두 번째 메시지 → current_status는 최신값 1행, history는 2건, 최신순 반환. */
    @Test
    void updateCurrentStatus_secondNewerMessage_updatesCurrentAndAppendsHistory() {
        insertVehicle("ISVC-02");
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime t2 = LocalDateTime.now().withNano(0);

        vehicleStatusService.updateCurrentStatus("ISVC-02",
                new VehicleStatusUpdateCommand("IDLE", 70, null, null, null, null, t1.atOffset(java.time.ZoneOffset.ofHours(9))));
        vehicleStatusService.updateCurrentStatus("ISVC-02",
                new VehicleStatusUpdateCommand("ACTIVE", 65, 1.0, 2.0, 45.0, 0.3, t2.atOffset(java.time.ZoneOffset.ofHours(9))));

        VehicleCurrentStatus cur = vehicleCurrentStatusMapper.findByVehicleId("ISVC-02").orElseThrow();
        assertThat(cur.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(cur.getBattery()).isEqualTo(65);
        assertThat(cur.getMessageAt()).isEqualTo(t2);

        List<VehicleStatusHistory> history = vehicleStatusHistoryMapper.findRecentByVehicleId("ISVC-02", 10);
        assertThat(history).hasSize(2);
        // 최신순(message_at DESC): 두 번째(ACTIVE)가 먼저, 첫 번째(IDLE)가 나중
        assertThat(history.get(0).getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(history.get(1).getStatus()).isEqualTo(VehicleStatus.IDLE);
    }

    /**
     * 테스트 3: 이미 T2 상태가 저장된 뒤 더 이른 T1 메시지 → stale로 무시. current_status 불변,
     * history 건수 불변. 코드상 stale로 판정되면 upsert·이력 insert 이전에 return 하므로 WebSocket
     * broadcast도 실행되지 않는다(관찰 가능한 정책 = DB 무변화로 검증; broadcast 미호출은 서비스가
     * broadcast 문장 이전에 return 하는 구조로 보장된다).
     */
    @Test
    void updateCurrentStatus_staleMessage_isIgnored() {
        insertVehicle("ISVC-03");
        LocalDateTime t1 = LocalDateTime.now().minusMinutes(1).withNano(0);
        LocalDateTime t2 = LocalDateTime.now().withNano(0);

        vehicleStatusService.updateCurrentStatus("ISVC-03",
                new VehicleStatusUpdateCommand("ACTIVE", 60, null, null, null, null, t2.atOffset(java.time.ZoneOffset.ofHours(9))));
        // 더 이른 T1 메시지(과거) — 무시되어야 함
        vehicleStatusService.updateCurrentStatus("ISVC-03",
                new VehicleStatusUpdateCommand("IDLE", 10, 9.0, 9.0, 9.0, 9.0, t1.atOffset(java.time.ZoneOffset.ofHours(9))));

        VehicleCurrentStatus cur = vehicleCurrentStatusMapper.findByVehicleId("ISVC-03").orElseThrow();
        assertThat(cur.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(cur.getBattery()).isEqualTo(60);
        assertThat(cur.getMessageAt()).isEqualTo(t2);

        assertThat(vehicleStatusHistoryMapper.findRecentByVehicleId("ISVC-03", 10)).hasSize(1);
    }

    /**
     * 테스트 4: 동일 messageAt 중복 메시지 → 현재 코드 정책상 stale과 동일하게 무시된다
     * ({@code isStale}가 {@code !incomingMessageAt.isAfter(existing)}로, 같은 시각은 "이후 아님"이라
     * stale로 간주). 따라서 최신 상태는 다시 갱신되지 않고 이력도 중복 추가되지 않는다. 이 테스트는
     * 그 현재 정책을 그대로 검증한다(억지로 실패시키지 않음, prompt14.md 4단계 지침).
     */
    @Test
    void updateCurrentStatus_duplicateSameTimestamp_isIgnored() {
        insertVehicle("ISVC-04");
        LocalDateTime t1 = LocalDateTime.now().withNano(0);

        vehicleStatusService.updateCurrentStatus("ISVC-04",
                new VehicleStatusUpdateCommand("ACTIVE", 50, null, null, null, null, t1.atOffset(java.time.ZoneOffset.ofHours(9))));
        // 같은 messageAt, 다른 값 — 무시되어야 함
        vehicleStatusService.updateCurrentStatus("ISVC-04",
                new VehicleStatusUpdateCommand("IDLE", 99, 5.0, 5.0, 5.0, 5.0, t1.atOffset(java.time.ZoneOffset.ofHours(9))));

        VehicleCurrentStatus cur = vehicleCurrentStatusMapper.findByVehicleId("ISVC-04").orElseThrow();
        assertThat(cur.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(cur.getBattery()).isEqualTo(50);

        assertThat(vehicleStatusHistoryMapper.findRecentByVehicleId("ISVC-04", 10)).hasSize(1);
    }

    private void insertVehicle(String vehicleId) {
        LocalDateTime now = LocalDateTime.now();
        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(vehicleId);
        vehicle.setName(vehicleId + " 이름");
        vehicle.setActive(true);
        vehicle.setCreatedAt(now);
        vehicleMapper.insert(vehicle);
    }
}
