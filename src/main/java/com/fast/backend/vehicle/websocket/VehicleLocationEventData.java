package com.fast.backend.vehicle.websocket;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;

/**
 * {@code VEHICLE_LOCATION_UPDATED} 이벤트의 {@code data} payload(prompt20.md 8장, prompt24.md 7장).
 *
 * <p>MQTT {@code ForkliftLocationMessage}(ROS2 규격: position/heading/quaternion/speed/status)를 이
 * 구조로 변환해서 보낸다({@code ForkliftLocationService} 참고). 이 프로젝트에는 아직 이 이벤트를 구독하는
 * 실제 프론트엔드 저장소가 없어(prompt20.md 10장, answer15/19 확인) 평면 필드 하위 호환을 별도로 유지할
 * 필요가 없었다 — prompt24.md 7장이 제시한 ROS2 규격 그대로(중첩 {@code position}, {@code quaternion})
 * 이 클래스를 확장했다(신규 버전 DTO를 별도로 만들지 않음, 기존 클래스 그대로 사용).
 *
 * <p>{@code status}는 MQTT payload의 원시 문자열을 {@link VehicleStatus#fromRaw(String)}로 정규화한
 * 값이다 — 위치 이벤트가 상태를 "표시"만 할 뿐 {@code vehicle_current_status}를 갱신하지 않는다는 원칙은
 * 그대로 유지한다(prompt24.md 6장, {@code ForkliftLocationService} Javadoc 참고). 여전히
 * {@code vehicle_current_status} 테이블에는 위치를 별도로 반영하지 않는다 — status 메시지와 location
 * 메시지가 서로 다른 MQTT 토픽으로 분리돼 있는데, 둘 다 같은 upsert(모든 컬럼을 매번 덮어씀)를 타면
 * 위치만 온 메시지가 기존 status를, status만 온 메시지가 기존 위치를 null로 지워버리는 문제가 생긴다.
 */
public record VehicleLocationEventData(
        String vehicleId,
        VehicleStatus status,
        Position position,
        Double heading,
        Quaternion quaternion,
        Double speed,
        OffsetDateTime messageAt,
        OffsetDateTime receivedAt
) {

    public record Position(Double x, Double y, String frameId) {
    }

    /** ROS2 원본 quaternion 값을 그대로 전달한다 — 백엔드에서 정규화 계산을 하지 않는다(prompt24.md 5장). */
    public record Quaternion(Double x, Double y, Double z, Double w) {
    }
}
