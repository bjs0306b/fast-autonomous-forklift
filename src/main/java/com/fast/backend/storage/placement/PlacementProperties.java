package com.fast.backend.storage.placement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 적재 위치 추천에 쓰는 설정값(prompt46.md 6장). {@code heightClearance}를 매직 넘버로 코드에 박지 않고
 * {@code storage.placement.height-clearance}로 외부화한다.
 *
 * <p>{@code heightClearance}: 화물 높이 위에 추가로 확보해야 하는 여유 높이(m). 적재 가능 판정 시
 * {@code cargo.height + heightClearance <= slot.height}로 쓴다. 값을 지정하지 않으면 0(여유 없음)이며,
 * 이는 prompt46.md 6장이 허용하는 기본값이다. 실제 운영 여유값은 팀 협의 대상이다(20장).
 */
@ConfigurationProperties(prefix = "storage.placement")
public record PlacementProperties(
        double heightClearance
) {
}
