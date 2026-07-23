package com.fast.backend.vehicle.websocket;

import java.time.LocalDateTime;

/**
 * {@code VEHICLE_COMMAND_RESULT_UPDATED} 이벤트의 {@code data} payload(prompt20.md 9장).
 *
 * <p>이번 작업 시점에는 명령/결과 도메인 자체가 구현돼 있지 않다 — {@code forklift/{vehicleId}/result}
 * MQTT 토픽이 구독 목록({@link com.fast.backend.config.mqtt.MqttConfig#mqttInboundAdapter}, 3개
 * 토픽만 구독)에 없고, 이 결과를 나타내는 MQTT 수신 DTO도 없다. prompt20.md 9장이 제시한 선택지 중
 * "A. WebSocket DTO와 Broadcaster만 추가"를 선택했다 — DB까지 확장하는 대신 이 DTO와
 * {@link VehicleWebSocketBroadcaster#broadcastCommandResult}만 미리 준비해 두고, 실제 명령 실행
 * 기능이 생기면 그 코드가 이 메서드를 그대로 호출하면 되도록 만들었다. 지금은 이 DTO를 생성해서
 * 브로드캐스트를 호출하는 코드가 존재하지 않는다(미구현, 프로덕션 경로에서 사용되지 않음).
 */
public record VehicleCommandResultEventData(
        String commandId,
        String vehicleId,
        String command,
        String resultStatus,
        String message,
        LocalDateTime completedAt
) {
}
