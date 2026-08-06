package com.fast.backend.vehicle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * FR-501-1의 "담당자 답변 이후 반영할 범위"를 지금 코드를 건드리지 않고 켤 수 있도록 하는 확장 포인트.
 * 두 플래그 모두 기본값은 비활성(false)이다(prompt16.md 3장·17장 조건).
 *
 * <ul>
 *   <li>{@code autoRegistrationEnabled}: MQTT로 들어온 미등록 vehicleId를 자동 등록할지 여부.</li>
 *   <li>{@code offlineCheckEnabled}/{@code offlineTimeoutSeconds}: 온라인·오프라인 자동 판정 Scheduler용.</li>
 * </ul>
 *
 * <p><b>⚠️ 세 값 모두 아직 아무 데서도 읽지 않는다</b>(2026-08-06 확인 — 참조처는
 * {@code VehicleConfig}의 {@code @EnableConfigurationProperties} 하나뿐이다).
 * <b>값을 바꿔도 동작이 안 바뀐다.</b> "자동 등록을 켰는데 왜 안 되나"로 시간을 쓰지 말 것.
 *
 * <p>종전 이 주석은 근거를 "MQTT 연동 자체가 아직 없음"이라고 적고 있었는데 <b>그건 틀렸다</b> —
 * MQTT 수신은 이미 돌고 있다({@code mqtt} 패키지 · {@code ForkliftStatusService}). 미등록 차량은
 * 자동 등록되는 게 아니라 {@code VEHICLE_NOT_FOUND}로 <b>경고 로그를 남기고 버려진다.</b>
 * 즉 지금 동작은 "플래그가 false인 것과 같다"가 아니라 "플래그와 무관하게 항상 버린다"이다.
 *
 * <p>바인딩 자체도 기본 프로필에는 없다 — {@code VEHICLE_*} 환경변수는
 * {@code application-local.yml}에만 매핑돼 있어 EC2 배포(기본 프로필)에서는 전부 {@code false}/0이다.
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
