package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Pallet;
import com.fast.backend.storage.domain.PalletStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Optional;

@Mapper
public interface PalletMapper {

    int insert(Pallet pallet);

    Optional<Pallet> findById(Long id);

    Optional<Pallet> findByPalletId(String palletId);

    boolean existsByPalletId(String palletId);

    int updateStatus(
            @Param("palletId") String palletId,
            @Param("status") PalletStatus status,
            @Param("updatedAt") LocalDateTime updatedAt);
}
