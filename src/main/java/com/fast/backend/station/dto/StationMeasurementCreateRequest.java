package com.fast.backend.station.dto;

/**
 * 측정 AI가 REST로 등록하는 최종 측정 결과다.
 *
 * <p>AI 내부 상세 결과를 통째로 받지 않고 백엔드가 저장하는 값만 camelCase로 받는다.
 *
 * <p>{@code sessionId}는 필수다. 세션 A가 해제된 후 도착한 A의 결과를 새 세션 B에 잘못
 * 연결하지 않도록 백엔드가 현재 활성 세션과 비교한다.
 *
 * <p>검증은 이 record가 아니라 {@code StationMeasurementService}가 한다 — status에 따라 어떤 필드가
 * 필수/null 이어야 하는지가 달라 Bean Validation 애너테이션 하나로는 표현되지 않는다.
 *
 * @param sessionId     이 측정이 속한 세션. 세션 생성 응답으로 받은 값을 그대로 보낸다.
 *                      현재 활성 세션과 다르면 409 {@code STATION_SESSION_MISMATCH} 로 거부된다 —
 *                      재전송할 때도 <b>측정 시점의 값을 유지</b>해야 한다(새 세션 값으로 바꾸면
 *                      막으려던 오귀속이 그대로 발생한다).
 * @param measurementId 외부 측정 결과 고유 식별자. 필수, blank 불가, 100자 이하, 중복 시 409.
 * @param status        {@code ok} / {@code dimensions_only} / {@code no_detection} / {@code unreliable}.
 *                      대소문자는 {@code StationMeasurementStatus#fromRaw}가 흡수한다.
 * @param cargoHeight   <b>화물만의 높이, 단위 meter</b>(팔레트 제외). 예: 72.3cm → {@code 0.723}.
 *                      백엔드는 cm/m를 추측해 변환하지 않는다 — AI 발행 경계에서 m로 보내야 한다.
 * @param tippingLevel  전복 위험 등급. 소문자({@code safe}) 입력을 허용하고 대문자로 정규화해 저장한다.
 * @param overhangRatio 팔레트 폭 대비 한쪽 최대 돌출 비율(무차원, 0 이상). 백엔드는 재계산하지 않는다.
 */
public record StationMeasurementCreateRequest(
        String sessionId,
        String measurementId,
        String status,
        Double cargoHeight,
        String tippingLevel,
        Double overhangRatio
) {
}
