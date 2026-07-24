package com.fast.backend.vehicle.websocket;

import com.fast.backend.command.websocket.VehicleCommandResultEventData;
import com.fast.backend.common.websocket.RealtimeEvent;
import com.fast.backend.common.websocket.RealtimeEventType;
import com.fast.backend.vehicle.dto.VehicleStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 차량 관련 실시간 이벤트를 STOMP로 브로드캐스트하는 유일한 통로(prompt20.md 10장).
 * 도메인 Service만 이 클래스를 호출하며, MQTT Receiver/Router는 이 클래스를 직접 알지 못한다 —
 * Broadcaster는 전송 책임만 갖는다.
 *
 * <p><b>공통 envelope 사용(prompt32.md 1장 13번 확정)</b>: 이전의 도메인 전용 봉투
 * ({@code VehicleWebSocketEvent}/{@code VehicleWebSocketEventType})를 폐지하고 AI·스테이션과 동일한
 * {@link RealtimeEvent}/{@link RealtimeEventType}을 쓴다. destination은 기존 경로를 그대로 유지한다 —
 * 목표는 payload 구조의 통일이지 경로 통합이 아니다.
 *
 * <p>모든 이벤트는 "전체 destination"과 "차량별 destination({@code /{id}})" 두 곳에 동시 전송된다.
 * {@code occurredAt}은 서버 처리 시각이 아니라 이벤트가 실제 발생한 시각을 {@code +09:00}
 * {@link OffsetDateTime}으로 담는다.
 *
 * <p>전송 실패는 여기서 잡아 로그만 남기고 절대 위로 던지지 않는다 — 이미 커밋된 DB 갱신 결과가
 * WebSocket 전송 실패 때문에 실패한 것처럼 보이면 안 되기 때문이다.
 */
@Component
public class VehicleWebSocketBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(VehicleWebSocketBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    public VehicleWebSocketBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastStatus(String vehicleId, VehicleStatusResponse data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_STATUS_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.STATUS_ALL, VehicleWebSocketTopics.status(vehicleId));
    }

    public void broadcastLocation(String vehicleId, VehicleLocationEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_LOCATION_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.LOCATION_ALL, VehicleWebSocketTopics.location(vehicleId));
    }

    /**
     * Isaac Sim 위치 메시지 전용. 기존 위치 destination과 eventType을 그대로 재사용한다 — 프론트가
     * "위치가 갱신됐다"는 사실을 출처(ROS2/Isaac)와 무관하게 하나의 이벤트 종류로 구독할 수 있게 하기
     * 위해서다. {@code data} 구조만 {@link IsaacVehicleLocationEventData}로 달라진다.
     */
    public void broadcastIsaacLocation(
            String vehicleId, IsaacVehicleLocationEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_LOCATION_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.LOCATION_ALL, VehicleWebSocketTopics.location(vehicleId));
    }

    /** Isaac Sim 상태 메시지 전용(LWT OFFLINE 포함). 기존 상태 destination을 재사용한다. */
    public void broadcastIsaacStatus(
            String vehicleId, IsaacVehicleStatusEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_STATUS_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.STATUS_ALL, VehicleWebSocketTopics.status(vehicleId));
    }

    /** Isaac Sim 경로 메시지 전용. */
    public void broadcastPath(String vehicleId, VehiclePathEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_PATH_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.PATH_ALL, VehicleWebSocketTopics.path(vehicleId));
    }

    /**
     * 명령 결과 전용. 이동·포크·비상정지 결과가 모두 이 하나의 메서드·이벤트 타입을 쓴다
     * (prompt32.md 1장 12번으로 결과 구조가 통합되면서, 이전의 {@code broadcastCommandResult} /
     * {@code broadcastEmbeddedCommandResult} 두 갈래도 하나로 합쳐졌다).
     */
    public void broadcastCommandResult(
            String vehicleId, VehicleCommandResultEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_COMMAND_RESULT_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.RESULT_ALL, VehicleWebSocketTopics.result(vehicleId));
    }

    /** 실물 포크 상태 전용. */
    public void broadcastForkStatus(
            String vehicleId, EmbeddedForkStatusEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_FORK_STATUS_UPDATED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.FORK_STATUS_ALL, VehicleWebSocketTopics.forkStatus(vehicleId));
    }

    /** 실물 임베디드 오류 전용. */
    public void broadcastEmbeddedError(
            String vehicleId, EmbeddedErrorEventData data, OffsetDateTime occurredAt) {
        send(RealtimeEventType.VEHICLE_ERROR_OCCURRED, vehicleId, occurredAt, data,
                VehicleWebSocketTopics.ERRORS_ALL, VehicleWebSocketTopics.errors(vehicleId));
    }

    private void send(
            RealtimeEventType eventType,
            String vehicleId,
            OffsetDateTime occurredAt,
            Object data,
            String allTopic,
            String vehicleTopic) {
        RealtimeEvent<Object> event = RealtimeEvent.of(eventType, vehicleId, occurredAt, data);
        try {
            messagingTemplate.convertAndSend(allTopic, event);
            messagingTemplate.convertAndSend(vehicleTopic, event);
            log.debug("Vehicle WebSocket event broadcast sent: vehicleId={}, eventType={}, topic={}",
                    vehicleId, eventType, allTopic);
        } catch (Exception e) {
            log.error("Failed to broadcast vehicle WebSocket event: vehicleId={}, topic={}, error={}",
                    vehicleId, allTopic, e.getMessage());
        }
    }
}
