package com.fast.backend.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주기 상태기계 설정 ({@code traffic.cycle.*}) — F팀 규격 {@code backend-control-impl} §1·§5·§6.
 *
 * <p>좌표는 모두 <b>시뮬 좌표계(20 × 30)</b>다. 실물의 10 배이고 백엔드는 변환하지 않는다.
 *
 * @param enabled         주기 진행을 켤지. 꺼 두면 차간 유지만 하고 목표는 보내지 않는다 —
 *                        F팀 데모와 병행할 때 서로 목표를 밀어내지 않게 하려는 스위치다
 * @param bay             입고 바이 좌표·진입 방향
 * @param exit            바이 탈출점
 * @param arriveTolM      스테이션 도달 판정(m)
 * @param approachTriggerM 스테이션 <b>진입점</b>까지 남은 호장이 이 값 이하가 되면 순환로를 벗어나
 *                        목적지로 직행한다. 규격 {@code APPROACH_TRIGGER}=4.0 —
 *                        그 전까지는 규칙 1(일방통행)에 따라 모서리를 하나씩 돈다
 * @param alignMinDwellMs 정렬 <b>최소</b> 대기(ms). 규격 {@code ALIGN_MIN_MS}=7000 —
 *                        정렬 자체가 최대 8초 걸리고, 지시 반영에도 200~400ms 가 든다
 * @param alignTimeoutMs  정렬 상한(ms). 규격 {@code ALIGN_MAX_MS}=12000 —
 *                        이 시간이 지나면 {@code busy} 와 무관하게 진행한다(안전장치)
 * @param loadTimeoutMs   적재 대기 상한(ms)
 * @param placeTimeoutMs  랙 적재 대기 상한(ms)
 * @param cargoHeightM    바이에서 받을 화물 높이(<b>실물 m</b>). 규격 §6 — height 만 실물 단위다
 * @param racks           차량별 랙 배정표. 키는 차량 ID, 값은 랙 코드 목록
 * @param dockX           랙 접두사(A/B)별 도킹 x 좌표. 규격 §7 의 PLACE_RACK 페이로드에 들어간다.
 *                        접근점(destination_x)과 달리 DB 에 두지 않았다 — 차량이 자체 수행하는
 *                        구간이라 백엔드가 "그리로 보내야 하는 것처럼" 읽히면 안 되기 때문이다
 * @param reverseDist     적재 후 후진 거리. 차량이 쓰는 값이고 백엔드는 전달만 한다
 * @param placementEnabled 화물 높이로 층을 고를지. 꺼 두면 {@code racks} 배정표 순서대로 돈다
 * @param cargoHeightScale telemetry 의 화물 높이(시뮬)를 실물 m 로 바꾸는 배수. 기본 0.1
 * @param cargoHeightRandomMinM 두 번째 주기부터 무작위로 뽑을 화물 높이의 하한(실물 m)
 * @param cargoHeightRandomMaxM 그 상한. 층 임계를 걸치게 잡아야 0층·1층이 섞여 나온다
 */
@ConfigurationProperties(prefix = "traffic.cycle")
public record CycleProperties(
        Boolean enabled,
        Station bay,
        Station exit,
        Double arriveTolM,
        Double approachTriggerM,
        Long alignMinDwellMs,
        Long alignTimeoutMs,
        Long loadTimeoutMs,
        Long placeTimeoutMs,
        Double cargoHeightM,
        Map<String, List<String>> racks,
        Map<String, Double> dockX,
        Double reverseDist,
        Map<String, Station> home,
        Boolean placementEnabled,
        Double cargoHeightScale,
        Double cargoHeightRandomMinM,
        Double cargoHeightRandomMaxM
) {

    /** 규격 §1 "스테이션". BAY(16.5, 5.0, yaw 0=동), EXIT(15.5, 4.0, yaw 1.5708=북). */
    private static final Station DEFAULT_BAY = new Station(16.5, 5.0, 0.0);
    private static final Station DEFAULT_EXIT = new Station(15.5, 4.0, 1.5708);

    /** 규격 §6 "랙 슬롯" — A 랙 도킹 x 2.60, B 랙 11.95. */
    private static final Map<String, Double> DEFAULT_DOCK_X = Map.of("A", 2.60, "B", 11.95);

    public CycleProperties {
        if (enabled == null) enabled = false;      // 기본은 꺼 둔다 — 켜는 것은 의식적 결정이어야 한다
        if (bay == null) bay = DEFAULT_BAY;
        if (exit == null) exit = DEFAULT_EXIT;
        if (arriveTolM == null || arriveTolM <= 0) arriveTolM = 1.5;
        if (approachTriggerM == null || approachTriggerM <= 0) approachTriggerM = 4.0;
        if (alignMinDwellMs == null || alignMinDwellMs < 0) alignMinDwellMs = 7_000L;
        if (alignTimeoutMs == null || alignTimeoutMs <= 0) alignTimeoutMs = 12_000L;
        if (alignMinDwellMs > alignTimeoutMs) {
            // 최소가 상한보다 크면 정렬 단계를 영영 못 벗어난다. 설정 오타를 기동 시점에 잡는다.
            throw new IllegalArgumentException(
                    "traffic.cycle.align-min-dwell-ms 는 align-timeout-ms 이하여야 합니다: "
                            + alignMinDwellMs + " > " + alignTimeoutMs);
        }
        if (loadTimeoutMs == null || loadTimeoutMs <= 0) loadTimeoutMs = 40_000L;
        if (placeTimeoutMs == null || placeTimeoutMs <= 0) placeTimeoutMs = 90_000L;
        if (cargoHeightM == null || cargoHeightM <= 0) cargoHeightM = 0.15;
        racks = racks == null ? Map.of() : Map.copyOf(racks);
        dockX = dockX == null || dockX.isEmpty() ? DEFAULT_DOCK_X : Map.copyOf(dockX);
        if (reverseDist == null || reverseDist <= 0) reverseDist = 2.0;
        home = home == null ? Map.of() : Map.copyOf(home);
        if (placementEnabled == null) placementEnabled = false;
        // 시뮬 단위 → 실물 m. 좌표계가 실물의 10 배다(seed-rack-slots.sql §1).
        if (cargoHeightScale == null || cargoHeightScale <= 0) cargoHeightScale = 0.1;
        if (cargoHeightRandomMinM == null || cargoHeightRandomMinM <= 0) cargoHeightRandomMinM = 0.07;
        if (cargoHeightRandomMaxM == null || cargoHeightRandomMaxM <= 0) cargoHeightRandomMaxM = 0.14;
        if (cargoHeightRandomMinM > cargoHeightRandomMaxM) {
            // 뒤집힌 범위를 그냥 두면 랜덤이 음수 폭을 갖는다. 기동 시점에 잡는다.
            throw new IllegalArgumentException(
                    "traffic.cycle.cargo-height-random-min-m 은 max 이하여야 합니다: "
                            + cargoHeightRandomMinM + " > " + cargoHeightRandomMaxM);
        }
    }

    /**
     * 이 차량의 복귀 지점(시작 위치). 없으면 빈 값.
     *
     * <p>비어 있으면 <b>복귀시키지 않는다.</b> 모르는 좌표로 보내느니 있던 자리에 세워 두는 편이
     * 낫다 — 짐작한 지점이 통로 한가운데면 다른 차를 막는다.
     */
    public java.util.Optional<Station> homeFor(String vehicleId) {
        if (vehicleId == null) {
            return java.util.Optional.empty();
        }
        for (Map.Entry<String, Station> entry : home.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(vehicleId)) {
                return java.util.Optional.ofNullable(entry.getValue());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 이 차량에 배정된 랙 목록. 설정에 없으면 빈 목록.
     *
     * <p>키 비교는 대소문자를 무시한다 — 별칭(`fk01` / `REAL-F01`)이 섞여 들어오는 일이 잦다.
     */
    public List<String> racksFor(String vehicleId) {
        if (vehicleId == null) {
            return List.of();
        }
        List<String> exact = racks.get(vehicleId);
        if (exact != null) {
            return exact;
        }
        Map<String, List<String>> lower = new LinkedHashMap<>();
        racks.forEach((key, value) -> lower.put(key.toLowerCase(), value));
        return lower.getOrDefault(vehicleId.toLowerCase(), List.of());
    }

    /**
     * 랙 코드의 도킹 x. 코드 첫 글자로 찾는다({@code A001} → {@code A}).
     *
     * <p>모르는 접두사면 빈 값 — 짐작한 좌표를 보내면 차량이 벽으로 간다.
     */
    public java.util.Optional<Double> dockXFor(String rackCode) {
        if (rackCode == null || rackCode.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(dockX.get(rackCode.substring(0, 1).toUpperCase()));
    }

    /** 스테이션 한 곳. {@code yaw} 는 라디안(규격이 라디안으로 준다). */
    public record Station(double x, double y, double yaw) {
    }
}
