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
}
