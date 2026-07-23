package com.fast.backend.vehicle.websocket;

import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 차량 관련 실시간 이벤트를 STOMP로 브로드캐스트하는 유일한 통로(prompt20.md 10장).
 * {@code VehicleStatusService}와 {@code ForkliftLocationService}만 이 클래스를 호출하며,
 * MQTT Receiver/Router는 이 클래스를 직접 알지 못한다 — Broadcaster는 전송 책임만 갖는다
 * (prompt20.md 18장 "Broadcaster는 전송 책임만 가질 것").
 *
 * <p>이전 이름은 {@code VehicleStatusBroadcaster}였다. 차량 위치·명령 결과까지 함께 보내는
 * 책임으로 넓어져 prompt20.md 10장이 제시한 이름·형태({@code VehicleWebSocketBroadcaster})로
 * 이번 작업에서 이름을 바꾸고 확장했다 — 상태 하나만 보내던 기존 브로드캐스터를 그대로 두고 별도
 * 클래스를 새로 만들면 같은 상태 갱신 이벤트가 서로 다른 토픽·형식으로 두 번 나가는 중복 경로가
 * 생기므로, prompt20.md 1장 "기존 구조를 최대한 유지하면서 부족한 부분만 보완할 것" 원칙에 따라
 * 기존 클래스를 확장하는 방식을 선택했다. 기존에 이 클래스를 실제로 구독하는 프론트엔드가 없었고
 * (React 프로젝트 자체가 이 저장소에 없음, answer15.md/answer19.md에서 이미 확인됨), 유일한
 * 호출자였던 {@code VehicleStatusService}도 이번 작업에서 함께 수정하므로 외부에 영향이 없다.
 *
 * <p>전송 실패는 여기서 잡아 로그만 남기고 절대 위로 던지지 않는다 — 이미 커밋된 DB 갱신 결과가
 * WebSocket 전송 실패 때문에 실패한 것처럼 보이면 안 되기 때문이다(기존
 * {@code MqttErrorChannelHandler}와 동일한 방어 패턴, 기존 {@code VehicleStatusBroadcaster}부터
 * 이어지는 결정).
 */
@Component
public class VehicleWebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(VehicleWebSocketBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public VehicleWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastStatus(String vehicleId, VehicleStatusResponse data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<VehicleStatusResponse> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_STATUS_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.STATUS_ALL, VehicleWebSocketTopics.status(vehicleId), vehicleId, event);
    }

    public void broadcastLocation(String vehicleId, VehicleLocationEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<VehicleLocationEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_LOCATION_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.LOCATION_ALL, VehicleWebSocketTopics.location(vehicleId), vehicleId, event);
    }

    /**
     * Isaac Sim 위치 메시지 전용(prompt28.md 3장·12장). 기존 위치 destination
     * ({@code /topic/vehicles/location}, {@code /topic/vehicles/location/{id}})을 그대로 재사용한다
     * — eventType도 {@code VEHICLE_LOCATION_UPDATED}로 동일하게 두어, 프론트가 "위치가 갱신됐다"는
     * 사실은 출처(ROS2/Isaac)와 무관하게 하나의 이벤트 종류로 구독할 수 있게 한다. payload 구조
     * (data 필드)만 {@link IsaacVehicleLocationEventData}로 달라진다.
     */
    public void broadcastIsaacLocation(String vehicleId, IsaacVehicleLocationEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<IsaacVehicleLocationEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_LOCATION_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.LOCATION_ALL, VehicleWebSocketTopics.location(vehicleId), vehicleId, event);
    }

    /**
     * Isaac Sim 상태 메시지 전용(prompt28.md 4장·5장·12장). 기존 상태 destination을 재사용한다(위
     * broadcastIsaacLocation과 동일한 이유). LWT OFFLINE 이벤트도 이 메서드로 전달된다.
     */
    public void broadcastIsaacStatus(String vehicleId, IsaacVehicleStatusEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<IsaacVehicleStatusEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_STATUS_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.STATUS_ALL, VehicleWebSocketTopics.status(vehicleId), vehicleId, event);
    }

    /**
     * Isaac Sim 경로 메시지 전용(prompt28.md 6장·12장). 경로는 기존에 대응하는 이벤트 타입·destination이
     * 없어 신규로 추가했다({@code VEHICLE_PATH_UPDATED}, {@code /topic/vehicles/path}).
     */
    public void broadcastPath(String vehicleId, VehiclePathEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<VehiclePathEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_PATH_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.PATH_ALL, VehicleWebSocketTopics.path(vehicleId), vehicleId, event);
    }

    /**
     * 명령/결과 도메인이 아직 구현되지 않아 이 메서드를 실제로 호출하는 프로덕션 코드는 현재 없다
     * (prompt20.md 9장 선택 A, {@link VehicleCommandResultEventData} Javadoc 참고). 명령 실행 기능이
     * 추가되면 이 메서드를 그대로 재사용하면 된다.
     */
    public void broadcastCommandResult(String vehicleId, VehicleCommandResultEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<VehicleCommandResultEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_COMMAND_RESULT_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.RESULT_ALL, VehicleWebSocketTopics.result(vehicleId), vehicleId, event);
    }

    /**
     * 실물(REAL01) 명령 결과 전용(prompt29.md 7장·19장). 기존 결과 destination·eventType을 그대로
     * 재사용한다 — 비상정지 결과도 이 이벤트 하나로 표현한다(19장 근거는
     * {@link EmbeddedCommandResultEventData} Javadoc 참고).
     */
    public void broadcastEmbeddedCommandResult(String vehicleId, EmbeddedCommandResultEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<EmbeddedCommandResultEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_COMMAND_RESULT_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.RESULT_ALL, VehicleWebSocketTopics.result(vehicleId), vehicleId, event);
    }

    /** 실물 포크 상태 전용(prompt29.md 8장·19장). 대응하는 기존 destination이 없어 신규로 추가했다. */
    public void broadcastForkStatus(String vehicleId, EmbeddedForkStatusEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<EmbeddedForkStatusEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_FORK_STATUS_UPDATED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.FORK_STATUS_ALL, VehicleWebSocketTopics.forkStatus(vehicleId), vehicleId, event);
    }

    /** 실물 임베디드 오류 전용(prompt29.md 9장·19장). 대응하는 기존 destination이 없어 신규로 추가했다. */
    public void broadcastEmbeddedError(String vehicleId, EmbeddedErrorEventData data, LocalDateTime occurredAt) {
        VehicleWebSocketEvent<EmbeddedErrorEventData> event = VehicleWebSocketEvent.of(
                VehicleWebSocketEventType.VEHICLE_ERROR_OCCURRED, vehicleId, occurredAt, data);
        send(VehicleWebSocketTopics.ERRORS_ALL, VehicleWebSocketTopics.errors(vehicleId), vehicleId, event);
    }

    private void send(String allTopic, String vehicleTopic, String vehicleId, Object event) {
        try {
            messagingTemplate.convertAndSend(allTopic, event);
            messagingTemplate.convertAndSend(vehicleTopic, event);
            log.debug("Vehicle WebSocket event broadcast sent: vehicleId={}, topic={}", vehicleId, allTopic);
        } catch (Exception e) {
            log.error("Failed to broadcast vehicle WebSocket event: vehicleId={}, topic={}, error={}",
                    vehicleId, allTopic, e.getMessage());
        }
    }
}
