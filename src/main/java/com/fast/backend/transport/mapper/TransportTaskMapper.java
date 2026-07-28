package com.fast.backend.transport.mapper;

import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.domain.TransportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface TransportTaskMapper {

    int insert(TransportTask task);

    Optional<TransportTask> findById(Long id);

    Optional<TransportTask> findByTaskCode(String taskCode);

    /** 필터(status/vehicleId/palletId)와 최신순 페이지네이션. null 필터는 무시된다. */
    List<TransportTask> findAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("palletId") String palletId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("palletId") String palletId);

    /**
     * 차량에 배정된 <b>진행 중</b>(종료 상태 제외) Task 전체를 한 번에 조회한다(prompt56.md 9·10장).
     *
     * <p>대시보드가 차량별 {@code currentTask}를 계산할 때 차량 수만큼 쿼리를 반복하지 않기 위한
     * <b>일괄 조회</b>다. {@code vehicle_id IS NULL}(미배정 PENDING)은 어느 차량에도 속하지 않으므로 제외한다.
     *
     * <p>정렬은 {@code vehicle_id, updated_at DESC, id DESC} — 각 vehicleId 그룹의 <b>첫 행이 곧 현재 작업</b>이
     * 되어 Service가 추가 정렬 없이 그대로 집을 수 있다. {@code updated_at}이 같은 경우 id 내림차순으로
     * 결정적(deterministic)으로 정해진다.
     *
     * <p>대시보드 {@code tasks} 목록(최근 100건)과 달리 <b>건수 제한이 없다</b> — 진행 중 작업은 차량 수
     * 규모로 자연히 제한되고, 100건 창 밖으로 밀려난 오래된 진행 작업도 놓치지 않아야 하기 때문이다.
     */
    List<TransportTask> findActiveTasksWithVehicle();

    /**
     * 조건부 배정(prompt47.md 9·14장). {@code status='PENDING' AND vehicle_id IS NULL}인 행만 갱신하므로,
     * 이미 배정됐거나 다른 요청이 먼저 배정한 Task는 update count=0이 되어 재배정을 막는다.
     */
    int updateAssignment(
            @Param("taskCode") String taskCode,
            @Param("vehicleId") String vehicleId,
            @Param("assignedAt") LocalDateTime assignedAt,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** 상태 전이 + 관련 timestamp 갱신(전이 유효성은 Service가 TaskStatus.validateTransition으로 사전 검증). */
    int updateStatus(
            @Param("taskCode") String taskCode,
            @Param("status") TaskStatus status,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("pickedUpAt") LocalDateTime pickedUpAt,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("failedAt") LocalDateTime failedAt,
            @Param("updatedAt") LocalDateTime updatedAt);

    boolean existsActiveTaskByVehicleId(String vehicleId);

    /** 활성(ASSIGNED~PLACING) Task만 — 배정 시 "다른 활성 작업이 이 팔레트를 쓰는가" 검사용. */
    boolean existsActiveTaskByPalletId(String palletId);

    /** 미종료(PENDING 포함) Task — 생성 시 "이 팔레트로 이미 진행 중/대기 작업이 있는가" 검사용. */
    boolean existsOpenTaskByPalletId(String palletId);

    boolean existsActiveTaskBySlotId(Long slotId);
}
