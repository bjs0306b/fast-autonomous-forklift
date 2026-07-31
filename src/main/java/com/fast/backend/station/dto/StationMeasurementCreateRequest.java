package com.fast.backend.station.dto;

import java.time.OffsetDateTime;

/**
 * 측정 데스크탑이 {@code POST /api/stations/measurements}로 보내는 측정 결과(prompt95.md 5장).
 *
 * <p><b>MQTT용 DTO를 재사용하지 않는다.</b> 옛 {@code StationMeasurementMessage}는 snake_case 원본
 * (detection/distance/miniature/loadBalance 상세)을 통째로 받았지만, 그중 저장되는 값은 네 개뿐이었다.
 * 이 DTO는 <b>저장되는 값만</b> camelCase로 받는다 — 받아서 버리는 필드를 계약에 남기지 않는다.
 *
 * <p><b>받지 않는 필드와 그 이유</b>
 * <ul>
 *   <li>{@code sessionId} — 백엔드가 현재 활성 세션을 조회해 붙인다(7장). 데스크탑이 세션을 알 필요가 없다.</li>
 *   <li>{@code stationId} — FR-202 스키마에서 컬럼이 사라졌다(측정 설비는 하나뿐이다).</li>
 *   <li>{@code schemaVersion} — REST 계약은 URL·DTO로 버전을 표현한다. 페이로드 안에 또 둘 이유가 없다.</li>
 * </ul>
 *
 * <p>검증은 이 record가 아니라 {@code StationMeasurementService}가 한다 — status에 따라 어떤 필드가
 * 필수/null 이어야 하는지가 달라서(15장) Bean Validation 애너테이션 하나로는 표현되지 않는다.
 *
 * @param measurementId 외부 측정 결과 고유 식별자. 필수, blank 불가, 100자 이하, 중복 시 409.
 * @param status        {@code ok} / {@code dimensions_only} / {@code no_detection} / {@code unreliable}.
 *                      대소문자는 {@code StationMeasurementStatus#fromRaw}가 흡수한다.
 * @param cargoHeight   <b>화물만의 높이, 단위 meter</b>(팔레트 제외). 예: 72.3cm → {@code 0.723}.
 *                      백엔드는 cm/m를 추측해 변환하지 않는다 — 데스크탑이 m로 보내야 한다.
 * @param tippingLevel  전복 위험 등급. 소문자({@code safe}) 입력을 허용하고 대문자로 정규화해 저장한다.
 * @param overhangRatio 팔레트 폭 대비 한쪽 최대 돌출 비율(무차원, 0 이상). 백엔드는 재계산하지 않는다.
 * @param measuredAt    장비 측정 시각(오프셋 포함). <b>현재 DB에 저장 컬럼이 없어 검증 후 버려진다</b> —
 *                      {@code created_at}(수신 시각)만 남는다. 컬럼 추가는 이번 범위 밖(5장).
 */
public record StationMeasurementCreateRequest(
        String measurementId,
        String status,
        Double cargoHeight,
        String tippingLevel,
        Double overhangRatio,
        OffsetDateTime measuredAt
) {
}
