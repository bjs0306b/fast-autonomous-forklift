package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Cargo;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface CargoMapper {

    int insert(Cargo cargo);
    int insertIfAbsent(Cargo cargo);

    Optional<Cargo> findByCargoId(String cargoId);

    boolean existsByCargoId(String cargoId);
}
