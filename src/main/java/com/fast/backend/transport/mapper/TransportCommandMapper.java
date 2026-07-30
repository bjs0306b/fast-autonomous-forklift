package com.fast.backend.transport.mapper;

import com.fast.backend.transport.domain.TransportCommand;
import com.fast.backend.transport.domain.TransportCommandStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code transport_command} 접근 — FR-202 최종 스키마(prompt85).
 *
 * <p>{@code published_at}/{@code acknowledged_at}/{@code updated_at} 컬럼이 사라져 해당 시각을 받는
 * 파라미터도 없어졌다. 발행·ACK 은 <b>상태 전이로만</b> 기록된다.
 */
@Mapper
public interface TransportCommandMapper {

    int insert(TransportCommand command);

    Optional<TransportCommand> findByCommandId(String commandId);

    List<TransportCommand> findByTaskId(Long taskId);

    Optional<TransportCommand> findLatestByTaskId(Long taskId);

    List<TransportCommand> findByTaskIds(@Param("taskIds") List<Long> taskIds);

    /** 아직 종결되지 않은(CREATED/PUBLISHED) 명령이 있는지. */
    boolean existsActiveByTaskId(Long taskId);

    int markPublished(@Param("commandId") String commandId);

    int markSucceeded(@Param("commandId") String commandId,
            @Param("completedAt") LocalDateTime completedAt);

    int markFailed(@Param("commandId") String commandId,
            @Param("failureReason") String failureReason,
            @Param("completedAt") LocalDateTime completedAt);

    int markPublishFailed(@Param("commandId") String commandId,
            @Param("failureReason") String failureReason);

    int markTimeout(@Param("commandId") String commandId,
            @Param("failureReason") String failureReason,
            @Param("completedAt") LocalDateTime completedAt);

    /** 현재 상태가 기대값일 때만 전이한다(중복 결과 메시지 방어). */
    int updateStatusIfCurrent(@Param("commandId") String commandId,
            @Param("expected") TransportCommandStatus expected,
            @Param("next") TransportCommandStatus next,
            @Param("failureReason") String failureReason,
            @Param("completedAt") LocalDateTime completedAt);
}
