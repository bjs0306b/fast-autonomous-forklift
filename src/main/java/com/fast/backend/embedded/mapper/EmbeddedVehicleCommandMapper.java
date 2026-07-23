package com.fast.backend.embedded.mapper;

import com.fast.backend.embedded.domain.EmbeddedVehicleCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface EmbeddedVehicleCommandMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 command.id가 채워진다. */
    int insert(EmbeddedVehicleCommand command);

    /** commandId 기준으로 상태·완료 시각·결과 필드를 갱신한다(발행 결과 반영, 명령 결과 반영 공용). */
    int update(EmbeddedVehicleCommand command);

    boolean existsByCommandId(String commandId);

    Optional<EmbeddedVehicleCommand> findByCommandId(String commandId);

    List<EmbeddedVehicleCommand> findRecentByForkliftId(
            @Param("forkliftId") String forkliftId, @Param("limit") int limit);
}
