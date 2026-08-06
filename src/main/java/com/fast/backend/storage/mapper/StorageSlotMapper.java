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
