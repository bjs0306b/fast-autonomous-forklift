package com.fast.backend.ai.mapper;

import com.fast.backend.ai.domain.AiCargoDetectionBox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiCargoDetectionBoxMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 box.id가 채워진다. */
    int insert(AiCargoDetectionBox box);

    List<AiCargoDetectionBox> findByAnalysisId(@Param("analysisId") Long analysisId);
}
