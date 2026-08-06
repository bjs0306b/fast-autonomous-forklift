package com.fast.backend.vehicle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

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
        long offlineTimeoutSeconds,

        /**
         * 외부 시스템이 쓰는 차량 ID → 이 프로젝트의 DB {@code vehicle_id} 별칭표.
         *
         * <p>Isaac Sim 은 {@code sim01} 로 발행하지만 DB 에 등록된 ID 는 {@code SIM-F01} 이다. 이 대응을
         * 코드에 {@code if} 로 박으면 차량이 늘 때마다 코드를 고쳐야 하고, 같은 매핑이 여러 파일에
         * 흩어진다. 그래서 설정 한 곳에만 둔다.
         *
         * <pre>
         * vehicle:
         *   id-aliases:
         *     sim01: SIM-F01
         *     sim02: SIM-F02
         * </pre>
         *
         * <p>키 비교는 대소문자를 무시한다({@code VehicleIdAliasResolver} 참고) — 발신 측이 {@code SIM01}
         * 로 바꿔 보내도 같은 차량으로 인식돼야 한다. <b>DB 의 {@code vehicle_id} 를 이 값에 맞춰 바꾸지
         * 않는다.</b> 정규화는 백엔드 경계에서만 일어나고 DB·프론트는 항상 DB 기준 ID 를 본다.
         */
        Map<String, String> idAliases
) {

    public VehicleProperties {
        idAliases = idAliases == null ? Map.of() : Map.copyOf(idAliases);
    }
}
