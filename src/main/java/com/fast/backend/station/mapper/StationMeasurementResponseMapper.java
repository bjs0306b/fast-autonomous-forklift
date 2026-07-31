package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.springframework.stereotype.Component;

/**
 * 저장된 측정 결과({@link StationMeasurement}) → 응답 DTO 변환(prompt95.md 12장).
 *
 * <p>옛 {@code StationMeasurementAdapter}를 대체한다. 그 클래스는 MQTT 수신 DTO
 * ({@code StationMeasurementMessage}) → 엔티티 변환을 함께 들고 있어서 station MQTT가 사라지면서
 * 절반이 무의미해졌다. 남은 절반(엔티티 → 응답)은 REST 조회와 WebSocket 브로드캐스트가 <b>공유</b>하므로
 * Service의 private 메서드로 내리지 않고 역할이 드러나는 이름으로 분리해 유지한다.
 *
 * <p>REST 요청 → 엔티티 변환은 여기 두지 않는다 — status별 검증·정규화와 활성 세션 조회가 함께
 * 일어나야 해서 {@code StationMeasurementService} 안에 있는 편이 흐름이 끊기지 않는다.
 */
@Component
public class StationMeasurementResponseMapper {

    /** 저장된 값만 응답으로 내보낸다. {@code cargoId}는 세션에서 온다(측정 행에는 없다). */
    public StationMeasurementResponse toResponse(StationMeasurement entity, String cargoId) {
        return new StationMeasurementResponse(
                entity.getSequenceNo(),
                entity.getMeasurementId(),
                entity.getSessionId(),
                cargoId,
                entity.getStatus(),
                entity.getCargoHeight(),
                entity.getTippingLevel(),
                entity.getOverhangRatio(),
                entity.getCreatedAt());
    }
}
