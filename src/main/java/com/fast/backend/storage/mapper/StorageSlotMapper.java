package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.StorageSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface StorageSlotMapper {

    int insert(StorageSlot slot);

    Optional<StorageSlot> findById(Long id);

    /** EMPTY 슬롯 + 소속 층·선반 정보를 조인해 추천 후보로 조회한다(prompt47.md 6장). */
    List<StorageSlotPlacementRow> findAllEmptySlotsForPlacement();

    /** 슬롯 1건 + 층·선반 정보 조인(상태 무관). Task 상세의 placement 표시에 사용한다. */
    Optional<StorageSlotPlacementRow> findPlacementRowById(Long slotId);

    /**
     * 조건부 예약(prompt47.md 5·14장). {@code status='EMPTY'}인 행만 RESERVED로 바꾸므로, 동시에 두
     * 요청이 같은 슬롯을 예약하려 하면 update count=1을 받은 한 건만 성공한다. 반환값이 1일 때만 예약 성공.
     */
    int reserveIfEmpty(
            @Param("slotId") Long slotId,
            @Param("reservedTaskId") String reservedTaskId,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** RESERVED → OCCUPIED (작업 완료). status='RESERVED'인 행만 갱신한다. */
    int markOccupied(
            @Param("slotId") Long slotId,
            @Param("storedCargoId") String storedCargoId,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** RESERVED → EMPTY (작업 실패·취소). reserved_task_id/stored_cargo_id를 비운다. */
    int releaseReservation(
            @Param("slotId") Long slotId,
            @Param("updatedAt") LocalDateTime updatedAt);

    int updateStoredCargo(
            @Param("slotId") Long slotId,
            @Param("storedCargoId") String storedCargoId,
            @Param("updatedAt") LocalDateTime updatedAt);
}
