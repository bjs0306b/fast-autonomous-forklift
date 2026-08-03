package com.fast.backend.station.dto;

/**
 * 화물 하나의 최근 측정 높이.
 *
 * <p>{@code station_measurement} 는 세션 단위 테이블이라 화물 식별자를 직접 갖지 않는다
 * ({@code station_session} 을 거쳐야 한다). 관제 화면은 "차량이 들고 있는 화물의 높이"를
 * 화물 식별자로 바로 찾아야 하므로, 조인 결과만 담는 전용 뷰를 둔다.
 *
 * @param cargoId    화물 식별자
 * @param cargoHeight 팔레트를 제외한 화물 높이(m). 측정 파이프라인이 저장한 값 그대로다.
 */
public record CargoMeasuredHeight(String cargoId, Double cargoHeight) {
}
