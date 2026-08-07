package com.fast.backend.station.dto;

/**
 * AI 가 검출한 상자 하나의 <b>이미지 픽셀 좌표</b>와 점수.
 *
 * <p>관제 화면이 측정 영상 위에 사각형을 그리는 데만 쓴다. 치수(높이·폭)는 여기 담지 않는다 —
 * 대표 치수는 {@code cargoHeight}/{@code cargoWidth} 로 따로 오고, 같은 값을 두 곳에 두면
 * 나중에 어느 쪽이 정본인지 헷갈린다.
 *
 * <p><b>이 좌표는 지게차 이동에 쓸 수 없다.</b> 카메라 화면 안의 픽셀 위치일 뿐이고, 창고 맵
 * 좌표로 바꾸려면 카메라 설치 위치·각도(외부 파라미터)가 필요한데 그 값은 어디에도 없다.
 * 차량을 보내는 좌표는 측정 높이로 고른 선반 슬롯의 {@code destination_x/y} 다.
 *
 * @param bboxPx {@code [x1, y1, x2, y2]} — 좌상단·우하단. 어느 해상도 기준인지는
 *               측정 응답의 {@code frameWidth}/{@code frameHeight} 를 봐야 한다
 * @param score  검출 신뢰도 0~1
 */
public record MeasurementBox(java.util.List<Integer> bboxPx, Double score) {
}
