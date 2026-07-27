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
