package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.Rack;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface RackMapper {

    int insert(Rack rack);

    Optional<Rack> findById(Long id);

    Optional<Rack> findByRackCode(String rackCode);
}
