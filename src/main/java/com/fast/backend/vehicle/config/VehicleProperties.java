package com.fast.backend.vehicle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FR-501-1의 "담당자 답변 이후 반영할 범위"를 지금 코드를 건드리지 않고 켤 수 있도록 하는 확장 포인트.
 * 두 플래그 모두 기본값은 비활성(false)이다(prompt16.md 3장·17장 조건).
 *
 * <ul>
 *   <li>{@code autoRegistrationEnabled}: MQTT로 들어온 미등록 vehicleId를 자동 등록할지 여부.
 *       지금은 이 값을 실제로 참조하는 로직이 없다(MQTT 연동 자체가 아직 없음) — 자동 등록 기능이
 *       구현될 때 이 플래그를 참조하도록 확장한다.</li>
 *   <li>{@code offlineCheckEnabled}/{@code offlineTimeoutSeconds}: 온라인·오프라인 자동 판정 Scheduler용.
 *       이번 Story에서는 Scheduler 자체를 구현하지 않는다(17장) — 설정값 자리만 미리 만들어둔다.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "vehicle")
public record VehicleProperties(
        boolean autoRegistrationEnabled,
        boolean offlineCheckEnabled,
        long offlineTimeoutSeconds
) {
}
