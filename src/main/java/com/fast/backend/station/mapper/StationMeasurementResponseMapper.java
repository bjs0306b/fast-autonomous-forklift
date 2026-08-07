package com.fast.backend.station.mapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.dto.MeasurementBox;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StationMeasurementResponseMapper {

    private static final Logger log = LoggerFactory.getLogger(StationMeasurementResponseMapper.class);

    private static final ObjectMapper BOXES_JSON = new ObjectMapper();
    private static final TypeReference<List<MeasurementBox>> BOX_LIST = new TypeReference<>() {
    };

    public StationMeasurementResponse toResponse(
            StationMeasurement entity, Long cargoId, boolean placementEligible) {
        return new StationMeasurementResponse(
                entity.getMeasurementId(),
                entity.getSessionId(),
                cargoId,
                entity.getStatus(),
                entity.getCargoHeight(),
                entity.getCargoWidth(),
                entity.getFrameWidth(),
                entity.getFrameHeight(),
                parseBoxes(entity.getBoxesJson(), entity.getMeasurementId()),
                entity.getTippingLevel(),
                entity.getOverhangRatio(),
                placementEligible,
                entity.getCreatedAt());
    }

    /**
     * 저장된 상자 JSON 을 되돌린다.
     *
     * <p><b>깨진 JSON 때문에 응답 전체를 실패시키지 않는다.</b> 상자는 화면에 사각형을 그리기
     * 위한 부가 정보라, 이것 하나로 측정 조회가 500 이 되면 치수·전복 판정까지 못 보게 된다.
     * 대신 빈 목록을 주고 경고를 남겨 조용한 유실은 막는다.
     */
    private List<MeasurementBox> parseBoxes(String boxesJson, String measurementId) {
        if (boxesJson == null || boxesJson.isBlank()) {
            return List.of();
        }
        try {
            List<MeasurementBox> boxes = BOXES_JSON.readValue(boxesJson, BOX_LIST);
            return boxes == null ? List.of() : boxes;
        } catch (Exception e) {
            log.warn("검출 상자 JSON 해석 실패(상자만 비우고 나머지는 그대로): measurementId={}, error={}",
                    measurementId, e.getMessage());
            return List.of();
        }
    }
}
