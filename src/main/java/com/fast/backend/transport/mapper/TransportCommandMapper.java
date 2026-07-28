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

    /**
     * 여러 taskId의 command를 <b>한 번의 쿼리로</b> 가져온다(prompt56.md 10장 N+1 방지).
     *
     * <p>이전에는 대시보드가 task 100건마다 {@link #findLatestByTaskId}를 호출해 최대 100회의 추가 쿼리가
     * 발생했다. 이 메서드는 그 100회를 1회로 줄인다.
     *
     * <p><b>"latest 1건"을 SQL이 아니라 Java에서 고르는 이유</b>: taskId별 상위 1건만 뽑으려면 윈도우 함수나
     * 상관 서브쿼리가 필요한데, 이 프로젝트는 MySQL(운영)과 H2 MySQL 모드(테스트) 양쪽에서 같은 SQL이
     * 돌아야 한다. 그래서 정렬만 SQL에 맡기고({@code task_id, created_at DESC, id DESC}) 그룹의 첫 행을
     * 호출자가 집도록 했다 — 두 DB 모두에서 동작이 보장되는 가장 단순한 방법이다.
     *
     * <p><b>주의</b>: {@code taskIds}가 비어 있으면 {@code IN ()}이 되어 SQL 문법 오류가 나므로,
     * 호출 전에 빈 목록을 걸러야 한다.
     *
     * @return {@code task_id} 오름차순, 같은 task 안에서는 최신순으로 정렬된 전체 command
     */
    List<TransportCommand> findByTaskIds(@Param("taskIds") List<Long> taskIds);

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
