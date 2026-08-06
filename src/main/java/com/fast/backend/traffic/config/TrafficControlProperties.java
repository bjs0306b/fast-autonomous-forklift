package com.fast.backend.traffic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * 차량 경로 충돌 예측·안전거리 제어(FR-502-1a) 설정.
 *
 * <p><b>⚠️ 기본값이 반드시 비활성({@code enabled=false})이어야 한다.</b> 이 기능은 관제가
 * <b>스스로 차량을 정지·재개</b>시키는 유일한 경로다. 설정을 건드리지 않은 환경에서 저절로 차량이
 * 멈추거나 움직이면 안 된다.
 *
 * <p><b>⚠️ {@link #vehicles} 가 비어 있으면 어떤 차량에도 명령을 보내지 않는다.</b> "전체 차량"을
 * 기본값으로 삼지 않는 이유는, 실물 차량이 <b>"명령이 끊기면 스스로 정지"하는 워치독</b>을 갖추기
 * 전까지 원격 정지 제어 대상에 들어가면 안 되기 때문이다. 시뮬레이션 차량으로 먼저 검증한 뒤
 * 하나씩 추가한다.
 *
 * @param enabled            기능 on/off. 기본 false
 * @param tickMs             판단 주기(ms)
 * @param vehicles           제어 대상 차량 ID. <b>비어 있으면 아무 명령도 보내지 않는다</b>
 * @param safeDistanceM      최소 안전거리(m). FR 의 "최소 안전거리"
 * @param holdDistanceM      정지 판단 거리(m). 안전거리에 여유를 더한 값이라 보통
 *                           {@code safeDistanceM} 이상이다
 * @param releaseHysteresis  재개 임계 배수. 재개는 {@code holdDistanceM × 이 값} 이상에서만 한다 —
 *                           같은 임계로 정지·재개를 판단하면 경계에서 STOP/RESUME 이 매 tick 번갈아
 *                           나가는 <b>채터링</b>이 생긴다
 * @param predictionHorizonS 예측 시간(초). 현재 속도·방향이 이만큼 유지된다고 보고 미래 위치를 잡는다
 * @param workZoneRadiusM    선반 작업 구역 반경(m). 선반 접근 좌표를 중심으로 한 원
 * @param staleLocationMs    위치가 이보다 오래됐으면 판단에서 제외한다. 낡은 좌표로 "안전하다"고
 *                           판정하는 것이 가장 위험하다
 */
@ConfigurationProperties(prefix = "traffic")
public record TrafficControlProperties(
        boolean enabled,
        long tickMs,
        List<String> vehicles,
        double safeDistanceM,
        double holdDistanceM,
        double releaseHysteresis,
        double predictionHorizonS,
        double workZoneRadiusM,
        long staleLocationMs
) {

    public TrafficControlProperties {
        vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
        if (tickMs <= 0) tickMs = 500;
        if (safeDistanceM <= 0) safeDistanceM = 6.0;
        if (holdDistanceM <= 0) holdDistanceM = Math.max(safeDistanceM, 9.0);
        if (releaseHysteresis < 1.0) releaseHysteresis = 1.2;
        if (predictionHorizonS <= 0) predictionHorizonS = 2.0;
        if (workZoneRadiusM <= 0) workZoneRadiusM = 3.0;
        if (staleLocationMs <= 0) staleLocationMs = 2000;
    }

    /** 재개 판단에 쓰는 거리(m). {@link #releaseHysteresis} 주석 참고. */
    public double releaseDistanceM() {
        return holdDistanceM * releaseHysteresis;
    }

    /** 이 차량을 제어해도 되는가. 목록이 비어 있으면 <b>항상 false</b>다(위 클래스 주석 참고). */
    public boolean controls(String vehicleId) {
        return vehicleId != null && controlledSet().contains(vehicleId);
    }

    private Set<String> controlledSet() {
        return Set.copyOf(vehicles);
    }
}
