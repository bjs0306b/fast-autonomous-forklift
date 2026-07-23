package com.fast.backend.vehicle.mqtt;

import com.fast.backend.vehicle.dto.VehicleStatusUpdateCommand;

/**
 * ROS2·Isaac Sim이 각각 다른 JSON 형식을 보낼 가능성을 대비한 확장 포인트(prompt16.md 18장).
 *
 * <p><b>이 인터페이스에는 구현체가 아직 없다.</b> 실제 JSON 규격이 합의되기 전까지는 가상의 DTO를
 * 만들지 않는다는 조건(18장 마지막 항목)에 따라, 지금은 "여기에 이런 모양의 확장 지점이 생길 것이다"만
 * 표시해둔다.
 *
 * <p>합의 이후 예상되는 사용 방식:
 * <pre>
 * MqttMessageRouter (기존 forklift/+/status 라우팅과 동일한 지점에 vehicle 토픽 분기 추가)
 *   → Ros2VehicleStatusAdapter implements VehicleStatusAdapter      (ROS2 JSON → Command)
 *   → IsaacSimVehicleStatusAdapter implements VehicleStatusAdapter  (Isaac Sim JSON → Command)
 *   → VehicleStatusService.updateCurrentStatus(vehicleId, command)
 * </pre>
 *
 * <p>{@code VehicleStatusService}는 이 인터페이스나 구현체의 존재 자체를 알 필요가 없다 — Adapter가
 * {@link VehicleStatusUpdateCommand}로 변환을 끝낸 뒤에만 Service를 호출하기 때문이다. 이 경계 덕분에
 * ROS2/Isaac Sim JSON 규격이 바뀌어도 Adapter 구현체만 수정하면 되고, Service/DB/WebSocket 계층은
 * 건드릴 필요가 없다.
 *
 * <p>TODO(담당자 답변 후): {@code Ros2VehicleStatusAdapter}, {@code IsaacSimVehicleStatusAdapter} 구현,
 * MqttMessageRouter에 vehicle 상태 토픽 분기 추가, 미등록 차량 수신 시 경고 로그 처리 정책 확정
 * (VehicleStatusService는 VEHICLE_NOT_FOUND를 던지므로, 이 Adapter/Router 쪽에서 그 예외를 잡아
 * "경고 로그 후 무시"로 다운그레이드할지 결정 — prompt16.md 3장 조건).
 */
public interface VehicleStatusAdapter {

    /**
     * MQTT 페이로드에서 vehicleId를 추출한다(토픽에서 추출할지 payload 필드에서 추출할지도
     * 실제 규격 합의 후 결정).
     */
    String extractVehicleId(String topic, String payload);

    /**
     * MQTT payload를 {@link VehicleStatusUpdateCommand}로 변환한다. 원본 JSON 구조를 알아야 하는
     * 유일한 지점이며, 이 메서드 밖에서는 ROS2/Isaac Sim JSON 구조에 의존하지 않는다.
     */
    VehicleStatusUpdateCommand toCommand(String topic, String payload);
}
