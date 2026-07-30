package com.fast.backend.transport.mapper;

import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code transport_task} 접근 — FR-202 최종 스키마(prompt85).
 *
 * <p>파렛트 기준 조회({@code existsActiveTaskByPalletId}, {@code existsOpenTaskByPalletId})가 사라지고
 * <b>화물 기준</b>({@code existsOpenTaskByCargoId})으로 대체됐다 — 파렛트 개념이 없어졌기 때문이다.
 * 슬롯 참조도 id → slot_code 로 바뀌었다.
 */
@Mapper
public interface TransportTaskMapper {

    int insert(TransportTask task);

    Optional<TransportTask> findById(Long id);

    Optional<TransportTask> findByTaskCode(String taskCode);

    List<TransportTask> findAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("size") int size,
            @Param("offset") int offset);

    long countAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId);

    /** 차량이 배정된 진행 중 작업(디스패치 타임아웃 점검 등에서 사용). */
    List<TransportTask> findActiveTasksWithVehicle();

    /** 조건부 배정. PENDING + vehicle_id IS NULL 인 행만 갱신하므로 경쟁 시 한 건만 이긴다. */
    int updateAssignment(
            @Param("taskCode") String taskCode,
            @Param("vehicleId") String vehicleId,
            @Param("assignedAt") LocalDateTime assignedAt);

    int updateStatus(
            @Param("taskCode") String taskCode,
            @Param("status") TaskStatus status,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("pickedUpAt") LocalDateTime pickedUpAt,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("failedAt") LocalDateTime failedAt);

    boolean existsActiveTaskByVehicleId(String vehicleId);

    /** 같은 화물에 이미 열린(미완료) 작업이 있는지. 파렛트 기준 검사를 대체한다. */
    boolean existsOpenTaskByCargoId(String cargoId);

    boolean existsActiveTaskBySlotCode(String slotCode);
}
