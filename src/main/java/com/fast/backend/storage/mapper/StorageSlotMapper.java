package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.StorageSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Optional;

@Mapper
public interface StorageSlotMapper {
    int insert(StorageSlot slot);
    Optional<StorageSlot> findBySlotCode(String slotCode);

    /**
     * 모든 적재 위치. 상태({@code EMPTY/OCCUPIED/…})와 무관하게 전부 돌려준다.
     *
     * <p>교통 관제({@code WorkZoneRegistry})가 <b>선반 좌표</b>를 알기 위해 쓴다 — 작업 구역은
     * 선반이 비었는지와 무관하게 존재하므로 {@code findAllEmptySlotsForPlacement} 로는 안 된다.
     */
    List<StorageSlot> findAll();
    List<StorageSlotPlacementRow> findAllEmptySlotsForPlacement();
    Optional<StorageSlotPlacementRow> findPlacementRowBySlotCode(String slotCode);
    int reserveIfEmpty(@Param("slotCode") String slotCode, @Param("reservedTaskId") Long reservedTaskId);
    int markOccupied(
            @Param("slotCode") String slotCode,
            @Param("reservedTaskId") Long reservedTaskId,
            @Param("storedCargoId") Long storedCargoId);
    int releaseReservation(
            @Param("slotCode") String slotCode,
            @Param("reservedTaskId") Long reservedTaskId);

    /** 등록된 칸 수 전체. 0 이면 "랙이 없는" 것이지 "가득 찬" 것이 아니다. */
    int countAll();

    /** 아직 비어 있는 칸 수. 0 이면 랙이 가득 찼다는 뜻이다. */
    int countEmpty();

    /**
     * 예약 없이 곧바로 적재 완료 처리한다 — <b>주기 상태기계 전용</b>.
     *
     * <p>{@link #markOccupied} 는 {@code RESERVED} + {@code reserved_task_id} 일치를 요구한다.
     * 그쪽은 "측정 → 적재 위치 예약 → 적재" 를 거치는 운반 작업 흐름이고, 예약한 작업만 그 칸을
     * 채울 수 있어야 하기 때문이다. 반면 주기 상태기계는 운반 작업 없이 차량별 배정표대로 돌아서
     * 예약 단계 자체가 없다. 그래서 {@code EMPTY} 에서 바로 넘어가는 경로를 따로 둔다.
     *
     * <p>{@code EMPTY} 조건은 남겨 둔다 — 두 흐름이 같은 칸을 동시에 채우려 할 때 늦은 쪽이
     * 조용히 덮어쓰지 않고 0 을 돌려받게 하려는 것이다.
     */
    int markOccupiedIfEmpty(@Param("slotCode") String slotCode);
}
