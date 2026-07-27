package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Cargo;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface CargoMapper {

    /** insert 후 XML의 useGeneratedKeys로 cargo.id가 채워진다. */
    int insert(Cargo cargo);

    Optional<Cargo> findById(Long id);

    Optional<Cargo> findByCargoId(String cargoId);

    boolean existsByCargoId(String cargoId);
}
