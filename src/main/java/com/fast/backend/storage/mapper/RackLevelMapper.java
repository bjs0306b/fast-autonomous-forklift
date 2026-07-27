package com.fast.backend.storage.mapper;

import com.fast.backend.storage.domain.RackLevel;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface RackLevelMapper {

    int insert(RackLevel rackLevel);

    Optional<RackLevel> findById(Long id);
}
