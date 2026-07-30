package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Cargo;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

/** {@code cargo}(FR-202 최종 스키마: cargo_id PK + created_at) 접근. */
@Mapper
public interface CargoMapper {

    int insert(Cargo cargo);

    Optional<Cargo> findByCargoId(String cargoId);

    boolean existsByCargoId(String cargoId);
}
