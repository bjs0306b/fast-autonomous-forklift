package com.fast.backend.vehicle.location;

import com.fast.backend.vehicle.domain.VehicleStatus;
import com.fast.backend.vehicle.websocket.VehicleLocationEventData;

import java.time.OffsetDateTime;

/**
 * 위치 공통 처리에 넘기는 입력. <b>발신 측 스키마와 무관한 형태</b>여야 한다.
 *
 * <p>지금은 두 계약이 이 하나로 모인다.
 * <ul>
 *   <li>ROS2 {@code forklift/{id}/location} — 중첩 position, degree heading</li>
 *   <li>Isaac Sim {@code fast/v1/vehicle/{id}/telemetry} — pose.x/y, <b>radian</b> yaw</li>
 * </ul>
 *
 * <p>단위는 이 지점에서 이미 통일돼 있어야 한다 — 좌표 m, {@code heading} <b>degree</b>, {@code speed} m/s.
 * radian → degree 같은 변환은 각 어댑터(예: {@code IsaacVehicleTelemetryService})가 책임진다. 공통
 * 처리기가 단위를 추측하기 시작하면 어느 경로가 어떤 단위를 보냈는지 아무도 알 수 없게 된다.
 *
 * @param vehicleId      <b>DB에 등록된 차량 ID</b>(별칭 정규화가 끝난 값)
 * @param heading        degree. 정규화는 공통 처리기가 한다([0,360))
 * @param reportedStatus 발신 측이 함께 보고한 상태. <b>표시용</b>이며 DB 상태를 갱신하지 않는다
 * @param quaternion     ROS2 원본 quaternion. 없으면 null
 */
public record VehicleLocationIngestion(
        String vehicleId,
        Double x,
        Double y,
        String frameId,
        Double heading,
        Double speed,
        OffsetDateTime messageAt,
        VehicleStatus reportedStatus,
        VehicleLocationEventData.Quaternion quaternion,
        Double forkHeight,
        Double battery,
        String reportedCargoId,
        String reportedTaskId,
        Boolean reportedLoaded,
        Double reportedCargoHeight
) {

    public VehicleLocationIngestion(
            String vehicleId,
            Double x,
            Double y,
            String frameId,
            Double heading,
            Double speed,
            OffsetDateTime messageAt,
            VehicleStatus reportedStatus,
            VehicleLocationEventData.Quaternion quaternion,
            Double forkHeight,
            Double battery,
            String reportedCargoId,
            String reportedTaskId) {
        this(vehicleId, x, y, frameId, heading, speed, messageAt, reportedStatus, quaternion,
                forkHeight, battery, reportedCargoId, reportedTaskId, null, null);
    }

    public VehicleLocationIngestion(
            String vehicleId,
            Double x,
            Double y,
            String frameId,
            Double heading,
            Double speed,
            OffsetDateTime messageAt,
            VehicleStatus reportedStatus,
            VehicleLocationEventData.Quaternion quaternion) {
        this(vehicleId, x, y, frameId, heading, speed, messageAt, reportedStatus, quaternion,
                null, null, null, null, null, null);
    }
}
