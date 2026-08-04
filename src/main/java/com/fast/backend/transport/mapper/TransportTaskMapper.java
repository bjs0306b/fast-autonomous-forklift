package com.fast.backend.transport.mapper;

import com.fast.backend.transport.domain.TaskFailureCode;
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
    Optional<TransportTask> findOldestPending();
    List<TransportTask> findAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("cargoId") Long cargoId,
            @Param("limit") int limit,
            @Param("offset") int offset);
    long countAll(
            @Param("status") TaskStatus status,
            @Param("vehicleId") String vehicleId,
            @Param("cargoId") Long cargoId);
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
    /**
     * 실패 전이를 <b>원인 코드와 함께</b> 기록한다.
     *
     * <p>{@link #updateStatusIfCurrent}에 파라미터를 늘리지 않고 별도 메서드를 둔 이유: 실패가 아닌
     * 전이(시작·측정 진입)까지 매번 null 코드를 넘기게 되고, 그러면 "코드가 없는 실패"를 호출부에서
     * 실수로 만들기 쉬워진다. 실패 경로만 이 메서드를 지나가게 한다.
     *
     * <p>조건부 UPDATE 이므로 이미 다른 상태로 넘어간 작업은 건드리지 않는다 — TTL 만료와 정상
     * 결과 수신이 경합해도 완료된 작업을 실패로 덮어쓰지 않는다(멱등성).
     *
     * @return 실제로 실패 처리된 행 수(0 이면 이미 다른 상태였다는 뜻)
     */
    int failWithCode(
            @Param("id") Long id,
            @Param("expectedStatus") TaskStatus expectedStatus,
            @Param("failedAt") LocalDateTime failedAt,
            @Param("failureCode") TaskFailureCode failureCode);
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
            @Param("measurementRequestedAt") LocalDateTime measurementRequestedAt,
            @Param("waitExpiredBefore") LocalDateTime waitExpiredBefore);
    Optional<TransportTask> findOldestMeasurementAwaitingRequest();
    List<TransportTask> findExpiredMeasurementRequests(
            @Param("expiredBefore") LocalDateTime expiredBefore);
    List<TransportTask> findExpiredMeasurementLaneWaits(
            @Param("expiredBefore") LocalDateTime expiredBefore);
    List<TransportTask> findExpiredMovesAwaitingResult(
            @Param("expiredBefore") LocalDateTime expiredBefore);
    int failMoveIfAwaitingResult(
            @Param("id") Long id,
            @Param("expiredBefore") LocalDateTime expiredBefore,
            @Param("failedAt") LocalDateTime failedAt,
            @Param("failureCode") TaskFailureCode failureCode);
    int failMeasurementLaneWaitIfExpired(
            @Param("id") Long id,
            @Param("expiredBefore") LocalDateTime expiredBefore,
            @Param("failedAt") LocalDateTime failedAt);
    /** 차량별 최근 실패 작업(관제 화면의 실패 경고용). 차량당 여러 건이면 최신이 앞에 온다. */
    List<TransportTask> findLatestFailedTasksWithVehicle(@Param("limit") int limit);
    Optional<TransportTask> findPendingMeasurementByCargoId(Long cargoId);
    Optional<TransportTask> findByMeasurementSessionId(String sessionId);
    int lockMeasurementLane();
    boolean existsMeasurementLaneBusy();
    boolean existsActiveTaskByVehicleId(String vehicleId);
    boolean existsOpenTaskByCargoId(Long cargoId);
    boolean existsActiveTaskBySlotCode(String slotCode);
}
