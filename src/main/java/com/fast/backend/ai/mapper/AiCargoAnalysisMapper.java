package com.fast.backend.ai.mapper;

import com.fast.backend.ai.domain.AiCargoAnalysis;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface AiCargoAnalysisMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 analysis.id가 채워진다. */
    int insert(AiCargoAnalysis analysis);

    boolean existsByAnalysisId(String analysisId);

    Optional<AiCargoAnalysis> findByAnalysisId(String analysisId);

    /** cargoId 기준 가장 최근(processed_at DESC) 분석 결과 1건. */
    Optional<AiCargoAnalysis> findLatestByCargoId(String cargoId);
}
