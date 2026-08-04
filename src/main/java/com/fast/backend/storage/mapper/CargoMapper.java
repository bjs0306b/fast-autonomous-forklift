package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Cargo;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface CargoMapper {

    int insert(Cargo cargo);
    Optional<Cargo> findByCargoId(Long cargoId);

    boolean existsByCargoId(Long cargoId);
}
