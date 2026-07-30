package com.fast.backend.vehicle.mapper;

import com.fast.backend.vehicle.domain.VehicleCurrentStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface VehicleCurrentStatusMapper {

    Optional<VehicleCurrentStatus> findByVehicleId(String vehicleId);

    List<VehicleCurrentStatus> findAllByVehicleIds(List<String> vehicleIds);

    /**
     * MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE}로 차량당 최신 상태 한 행만 유지한다
     * (vehicle_id가 PK, prompt16.md 7장 조건). "오래된 메시지가 최신 상태를 덮어쓰지 않도록" 하는 비교는
     * SQL이 아니라 {@link com.fast.backend.vehicle.service.VehicleStatusService}에서 upsert 호출 전에
     * 수행한다 — SQL에서 비교하면 실패를 조용히 무시한 것인지 실제로 갱신된 것인지 구분하기 어렵기 때문이다.
     */
    void upsert(VehicleCurrentStatus status);

    /**
     * 위치 메시지({@code forklift/{vehicleId}/location})만으로 <b>위치 컬럼만</b> 갱신한다.
     *
     * <p>{@link #upsert}를 재사용하지 않는 이유: 그 메서드는 전체 상태 한 벌을 쓰기 때문에 위치
     * 메시지에 없는 battery·speed·fork·Isaac 확장 필드를 null 로 지운다.
     *
     * <p>행이 없으면 {@code INSERT}(status='UNKNOWN'), 있으면 위치 컬럼만 {@code UPDATE} 한다.
     *
     * <p><b>오래된 메시지 차단은 두 겹이다</b>(prompt86 A안).
     * 서비스가 절대시각으로 먼저 비교하고, 이 SQL 도 {@code ON DUPLICATE KEY UPDATE} 안에서 기존
     * {@code message_at} 과 다시 비교한다 — 두 메시지가 거의 동시에 처리되면 서비스 검사만으로는
     * 둘 다 통과할 수 있기 때문이다. 조건을 만족하지 못하면 <b>어떤 컬럼도 바뀌지 않는다</b>
     * ({@code received_at} 포함 — 15항의 received_at 정의를 지킨다).
     *
     * @return 영향받은 행 수. <b>이 값으로 stale 여부를 판단하지 말 것</b> — MySQL 설정
     *         (CLIENT_FOUND_ROWS)에 따라 "변경된 행 수"와 "매칭된 행 수" 중 무엇이 오는지 달라진다.
     */
    int updateLocation(@Param("vehicleId") String vehicleId,
            @Param("positionX") Double positionX,
            @Param("positionY") Double positionY,
            @Param("heading") Double heading,
            @Param("messageAt") LocalDateTime messageAt,
            @Param("receivedAt") LocalDateTime receivedAt);

    /**
     * 포크 상태 메시지({@code forklift/{vehicleId}/fork-status})로 포크 컬럼만 갱신한다.
     *
     * <p>구 {@code vehicle_fork_current_status} 테이블을 대체한다(FR-202 스키마에서 흡수됨).
     * {@code limit_bottom} 은 대응 컬럼이 없어 저장하지 않고 WebSocket 중계로만 쓴다.
     */
    int updateForkStatus(@Param("vehicleId") String vehicleId,
            @Param("forkState") String forkState,
            @Param("forkErrorCode") String forkErrorCode,
            @Param("receivedAt") LocalDateTime receivedAt);

    /**
     * 활성 차량만 대상으로 상태별 차량 수를 집계한다. 상태 이력이 아예 없는(vehicle_current_status에
     * 행이 없는) 활성 차량도 LEFT JOIN + COALESCE로 UNKNOWN에 포함된다(prompt16.md 14장 분석 결과).
     */
    List<VehicleStatusCountRow> countByStatusForActiveVehicles();
}
