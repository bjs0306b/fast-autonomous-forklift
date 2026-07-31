package com.fast.backend.station.mapper;

import com.fast.backend.station.domain.StationMeasurement;
import com.fast.backend.station.dto.StationMeasurementResponse;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 저장된 측정 결과({@link StationMeasurement}) → 응답 DTO 변환(prompt95.md 12장, prompt96).
 *
 * <p>옛 {@code StationMeasurementAdapter}를 대체한다. 그 클래스는 MQTT 수신 DTO
 * ({@code StationMeasurementMessage}) → 엔티티 변환을 함께 들고 있어서 station MQTT가 사라지면서
 * 절반이 무의미해졌다. 남은 절반(엔티티 → 응답)은 REST 조회와 WebSocket 브로드캐스트가 <b>공유</b>하므로
 * Service의 private 메서드로 내리지 않고 역할이 드러나는 이름으로 분리해 유지한다.
 *
 * <p>REST 요청 → 엔티티 변환은 여기 두지 않는다 — status별 검증·정규화와 활성 세션 조회가 함께
 * 일어나야 해서 {@code StationMeasurementService} 안에 있는 편이 흐름이 끊기지 않는다.
 *
 * <p><b>MQTT 시절 상세 블록은 채우지 않는다.</b> {@code detection}/{@code distance}/{@code dimensions}/
 * {@code loadBalance}는 REST 로 저장된 행에 대응하는 값이 없어 {@code null}로 둔다 — 없는 값을
 * 빈 객체로 만들어 "측정했지만 아무것도 못 봤다"처럼 보이게 하지 않는다.
 */
@Component
public class StationMeasurementResponseMapper {

    /**
     * @param cargoId          세션이 가리키는 화물(측정 행에는 없다). 세션이 없으면 null.
     * @param placementEligible 이 측정으로 적재 추천이 가능한지(안전 게이트 판정 결과)
     */
    public StationMeasurementResponse toResponse(StationMeasurement entity, String cargoId,
            boolean placementEligible) {
        return new StationMeasurementResponse(
                entity.getMeasurementId(),
                entity.getStationId(),
                entity.getSchemaVersion(),
                restoreMeasuredAt(entity),
                entity.getStatus(),
                null,
                null,
                null,
                null,
                entity.getReceivedAt(),
                entity.getSessionId(),
                cargoId,
                entity.getCargoHeight(),
                entity.getTippingLevel(),
                entity.getOverhangRatio(),
                placementEligible);
    }

    /**
     * UTC 시각 + 오프셋 분을 원래 {@link OffsetDateTime}으로 되돌린다 — DATETIME 컬럼이 타임존을
     * 담지 못해 두 컬럼으로 나눠 저장했기 때문이다. 둘 중 하나라도 없으면 복원하지 않는다.
     */
    private OffsetDateTime restoreMeasuredAt(StationMeasurement entity) {
        if (entity.getMeasuredAtUtc() == null || entity.getMeasuredAtOffsetMinutes() == null) {
            return null;
        }
        ZoneOffset offset = ZoneOffset.ofTotalSeconds(entity.getMeasuredAtOffsetMinutes() * 60);
        return entity.getMeasuredAtUtc().atOffset(ZoneOffset.UTC).withOffsetSameInstant(offset);
    }
}
