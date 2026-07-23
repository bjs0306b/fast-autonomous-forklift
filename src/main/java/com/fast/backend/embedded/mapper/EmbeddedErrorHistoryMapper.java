package com.fast.backend.embedded.mapper;

import com.fast.backend.embedded.domain.EmbeddedErrorHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmbeddedErrorHistoryMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 history.id가 채워진다. */
    int insert(EmbeddedErrorHistory history);

    List<EmbeddedErrorHistory> findRecentByForkliftId(
            @Param("forkliftId") String forkliftId, @Param("limit") int limit);
}
