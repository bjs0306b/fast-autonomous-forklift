package com.fast.backend.transport.mapper;

import com.fast.backend.transport.domain.TransportCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 운반 명령 Mapper(prompt48.md 7장). 상태 변경은 <b>조건부 UPDATE</b>로 구현해 update count=1일 때만
 * 전이 성공으로 보고, 이미 종료된 command에 대한 중복 결과는 count=0으로 자연히 무시된다(멱등성).
 */
@Mapper
public interface TransportCommandMapper {

    int insert(TransportCommand command);

    Optional<TransportCommand> findById(Long id);

    Optional<TransportCommand> findByCommandId(String commandId);

    List<TransportCommand> findByTaskId(Long taskId);

    /** taskId별 가장 최근 command 1건(created_at DESC). 대시보드 최신 command 상태 표시용(prompt50.md 11장). */
    Optional<TransportCommand> findLatestByTaskId(Long taskId);

    /** 진행 중(CREATED/PUBLISHED/ACKNOWLEDGED) command가 있는지 — 중복 디스패치 차단용. */
    boolean existsActiveByTaskId(Long taskId);

    /** CREATED → PUBLISHED. */
    int markPublished(@Param("commandId") String commandId,
                      @Param("publishedAt") LocalDateTime publishedAt,
                      @Param("updatedAt") LocalDateTime updatedAt);

    /** PUBLISHED → ACKNOWLEDGED. */
    int markAcknowledged(@Param("commandId") String commandId,
                         @Param("acknowledgedAt") LocalDateTime acknowledgedAt,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /** PUBLISHED/ACKNOWLEDGED → SUCCEEDED (종료). count=1일 때만 최종 성공 반영. */
    int markSucceeded(@Param("commandId") String commandId,
                      @Param("completedAt") LocalDateTime completedAt,
                      @Param("updatedAt") LocalDateTime updatedAt);

    /** PUBLISHED/ACKNOWLEDGED → FAILED (종료). */
    int markFailed(@Param("commandId") String commandId,
                   @Param("failureReason") String failureReason,
                   @Param("completedAt") LocalDateTime completedAt,
                   @Param("updatedAt") LocalDateTime updatedAt);

    /** CREATED → PUBLISH_FAILED (발행 자체 실패). */
    int markPublishFailed(@Param("commandId") String commandId,
                          @Param("failureReason") String failureReason,
                          @Param("updatedAt") LocalDateTime updatedAt);

    /** PUBLISHED/ACKNOWLEDGED → TIMEOUT (결과 미수신 만료). */
    int markTimeout(@Param("commandId") String commandId,
                    @Param("updatedAt") LocalDateTime updatedAt);

    /** 범용 조건부 상태 전이: 현재 상태가 {@code fromStatus}일 때만 {@code toStatus}로 바꾼다. */
    int updateStatusIfCurrent(@Param("commandId") String commandId,
                              @Param("fromStatus") String fromStatus,
                              @Param("toStatus") String toStatus,
                              @Param("updatedAt") LocalDateTime updatedAt);
}
