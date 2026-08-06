package com.fast.backend.traffic.domain;

import com.fast.backend.vehicle.domain.VehicleStatus;

/**
 * 충돌 판단에 필요한 차량 상태 한 장. {@code VehicleLocationSnapshot} 에서 필요한 값만 뽑아 온 것으로,
 * <b>이 패키지의 판단 로직이 위치 저장 구조에 묶이지 않게</b> 하는 경계다.
 *
 * @param headingDeg 진행 방향(degree, 0=+X축, 반시계). 위치 계약이 degree 라 그대로 받는다
 * @param speedMps   속도(m/s). {@code null} 이면 <b>모른다</b>는 뜻이며 0(정지)으로 단정하지 않는다 —
 *                   "모르는데 멈춰 있다고 가정"하면 예측 거리가 실제보다 길게 나와 위험하다
 */
public record VehicleMotion(
        String vehicleId,
        double x,
        double y,
        Double headingDeg,
        Double speedMps,
        VehicleStatus status
) {

    /** 지금 선반에서 하역·적재 작업 중인가. 이 상태면 그 자리를 점유한 것으로 본다. */
    public boolean isWorking() {
        return status == VehicleStatus.LOADING
                || status == VehicleStatus.UNLOADING
                || status == VehicleStatus.LIFTING;
    }

    /** 관제가 이미 세워 둔 차량인가. */
    public boolean isHeld() {
        return status == VehicleStatus.HOLDING;
    }

    /**
     * 예측에 쓸 속도(m/s).
     *
     * <p>속도를 모르면({@code null}) <b>0 이 아니라 보수적인 기본값</b>이 필요하지만, 그 값을 이
     * 레코드가 정할 수는 없다(설정 소관). 그래서 여기서는 "모른다"를 그대로 드러내고
     * {@link CollisionPredictor} 가 판단하게 한다.
     */
    public boolean hasKnownVelocity() {
        return speedMps != null && headingDeg != null;
    }
}
