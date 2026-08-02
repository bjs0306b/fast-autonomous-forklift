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
    List<TransportTask> findAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("cargoId") String cargoId,
            @Param("limit") int limit,
            @Param("offset") int offset);
    long countAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("cargoId") String cargoId);
    List<TransportTask> findActiveTasksWithVehicle();
    int updateAssignment(
            @Param("taskCode") String taskCode,
            @Param("vehicleId") String vehicleId,
            @Param("assignedAt") LocalDateTime assignedAt);
    int updateStatus(
            @Param("taskCode") String taskCode,
            @Param("status") TaskStatus status,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("failedAt") LocalDateTime failedAt);
    int updateStatusIfCurrent(
            @Param("id") Long id,
            @Param("expectedStatus") TaskStatus expectedStatus,
            @Param("targetStatus") TaskStatus targetStatus,
            @Param("startedAt") LocalDateTime startedAt,
            @Param("failedAt") LocalDateTime failedAt);
    int completeMeasurement(
            @Param("id") Long id,
            @Param("measurementId") String measurementId,
            @Param("destinationSlotCode") String destinationSlotCode,
            @Param("destinationX") Double destinationX,
            @Param("destinationY") Double destinationY,
            @Param("destinationHeading") Double destinationHeading,
            @Param("forkHeight") Double forkHeight);
    int startMeasurement(@Param("id") Long id, @Param("sessionId") String sessionId);
    int markMeasurementRequested(
            @Param("id") Long id,
            @Param("measurementRequestedAt") LocalDateTime measurementRequestedAt);
    List<TransportTask> findExpiredMeasurementRequests(
            @Param("expiredBefore") LocalDateTime expiredBefore);
    List<TransportTask> findExpiredMovesAwaitingResult(
            @Param("expiredBefore") LocalDateTime expiredBefore);
    int failMoveIfAwaitingResult(
            @Param("id") Long id,
            @Param("expiredBefore") LocalDateTime expiredBefore,
            @Param("failedAt") LocalDateTime failedAt);
    Optional<TransportTask> findPendingMeasurementByCargoId(String cargoId);
    Optional<TransportTask> findByMeasurementSessionId(String sessionId);
    int lockMeasurementLane();
    boolean existsMeasurementLaneBusy();
    boolean existsActiveTaskByVehicleId(String vehicleId);
    boolean existsOpenTaskByCargoId(String cargoId);
    boolean existsActiveTaskBySlotCode(String slotCode);
}
