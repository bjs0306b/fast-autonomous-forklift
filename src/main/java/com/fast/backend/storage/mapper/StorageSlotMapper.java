package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.StorageSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code storage_slot} 접근 — FR-202 최종 스키마(prompt85).
 *
 * <p>랙 계층이 사라져 조인 읽기 모델({@code StorageSlotPlacementRow})도 함께 없어졌다.
 * 슬롯 한 행이 곧 추천 후보다.
 */
@Mapper
public interface StorageSlotMapper {

    int insert(StorageSlot slot);

    Optional<StorageSlot> findBySlotCode(String slotCode);

    /** 추천 대상(EMPTY) 슬롯 전체. */
    List<StorageSlot> findAllEmptySlots();

    /**
     * 조건부 예약. {@code status='EMPTY'} 인 행만 RESERVED 로 바꾸므로, 두 요청이 같은 슬롯을
     * 예약하려 하면 반환값 1 을 받은 한 건만 성공한다.
     */
    int reserveIfEmpty(
            @Param("slotCode") String slotCode,
            @Param("reservedTaskId") Long reservedTaskId);

    /**
     * RESERVED → OCCUPIED (작업 완료).
     *
     * <p>최종 스키마의 CHECK 제약({@code chk_storage_slot_state_shape})이
     * "OCCUPIED 면 reserved_task_id 는 NULL" 을 강제하므로 예약을 반드시 함께 비운다.
     */
    int markOccupied(
            @Param("slotCode") String slotCode,
            @Param("storedCargoId") String storedCargoId);

    /** RESERVED → EMPTY (작업 실패·취소). 예약·적재 컬럼을 비운다. */
    int releaseReservation(@Param("slotCode") String slotCode);
}
