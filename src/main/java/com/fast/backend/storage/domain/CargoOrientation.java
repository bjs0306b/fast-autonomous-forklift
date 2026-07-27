package com.fast.backend.storage.domain;

/**
 * 화물을 슬롯에 놓을 수 있는 두 가지 배치 방향(prompt46.md 6장).
 *
 * <ul>
 *   <li>{@link #NORMAL} — 화물 가로가 슬롯 가로, 화물 세로가 슬롯 세로에 대응</li>
 *   <li>{@link #ROTATED_90} — 90도 회전. 화물 세로가 슬롯 가로, 화물 가로가 슬롯 세로에 대응</li>
 * </ul>
 *
 * <p>회전 허용 여부 자체는 팀 협의 대상이다(prompt46.md 20장). 이 enum은 "회전을 허용한다는 전제
 * 하에서" 두 방향을 표현할 뿐이며, 실제 적재 가능 판정과 방향 선택은
 * {@link com.fast.backend.storage.placement.PlacementService}가 수행한다.
 */
public enum CargoOrientation {
    NORMAL,
    ROTATED_90
}
