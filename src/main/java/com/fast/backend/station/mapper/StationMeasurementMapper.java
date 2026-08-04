package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.dto.CargoMeasuredHeight;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface StationMeasurementMapper {

    /** insert 후 MyBatis useGeneratedKeys 설정(XML)에 의해 measurement.id가 채워진다. */
    int insert(StationMeasurement measurement);

    boolean existsByMeasurementId(String measurementId);

    Optional<StationMeasurement> findByMeasurementId(String measurementId);

    /**
     * 세션에 측정 결과가 이미 저장돼 있는지(prompt96).
     *
     * <p>두 곳에서 쓴다 — 세션당 최종 결과 1건 정책(5장)과, 측정 저장 전 세션 종료 차단(3장).
     * 두 번째 용도에서는 status 를 보지 않는다: DIMENSIONS_ONLY/NO_DETECTION/UNRELIABLE 도
     * "측정 결과가 저장됨"에 해당하므로 세션을 종료할 수 있다.
     */
    boolean existsBySessionId(String sessionId);

    /** 세션의 최신 측정 결과 1건. 세션당 1건 정책이라 사실상 그 세션의 유일한 결과다. */
    Optional<StationMeasurement> findLatestBySessionId(String sessionId);

    /**
     * 여러 화물의 최근 측정 높이를 <b>한 번의 쿼리로</b> 조회한다(관제 대시보드용).
     *
     * <p>대시보드는 차량 N대의 적재 화물 높이를 동시에 필요로 한다. 차량마다 조회하면 그대로
     * N+1 이 되므로 화물 식별자 목록을 IN 으로 묶는다.
     *
     * <p>화물 하나에 측정 세션이 여러 번 생길 수 있어 결과가 화물당 여러 행일 수 있다.
     * 정렬을 SQL 에서 보장하고(오래된 것 → 최신), 호출부가 뒤 행으로 덮어써 최신 1건을 남긴다.
     * 높이가 없는 측정({@code NO_DETECTION} 등)은 제외한다 — 재측정이 높이를 못 냈다고 해서
     * 이미 확인된 높이를 "측정 정보 없음"으로 되돌리는 편이 더 부정확하다.
     *
     * @param cargoIds 조회할 화물 식별자. <b>비어 있으면 호출하지 말 것</b>(IN () 는 문법 오류다).
     */
    List<CargoMeasuredHeight> findLatestCargoHeights(@Param("cargoIds") List<Long> cargoIds);

}
