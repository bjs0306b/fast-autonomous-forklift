package com.fast.backend.command.websocket;

import com.fast.backend.command.dto.VehicleCommandResponse;
import com.fast.backend.common.time.CommunicationTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 안전 명령 발행/요약 이벤트 STOMP 브로드캐스트(prompt53.md 13장). 기존 {@code VehicleWebSocketBroadcaster}
 * 패턴을 따라 공통 + 차량별 토픽으로 보내고 전송 실패는 로그만 남긴다.
 *
 * <p><b>발행(publish) 시점 이벤트만</b> 이 토픽으로 보낸다. 명령 <b>결과(SUCCEEDED/FAILED)</b>는 기존
 * {@code VehicleCommandResultService}가 이미 {@code VEHICLE_COMMAND_RESULT_UPDATED}로
 * {@code /topic/vehicles/result}에 보내므로 중복 발행하지 않는다(§13 "같은 이벤트 중복 발행 금지").
 *
 * <p>발행은 {@code VehicleCommandService.issueCommand}가 자체 트랜잭션에서 DB 저장까지 커밋한 <b>이후</b>
 * 호출되므로(별도 빈 호출 반환 시점), 커밋 후 전송 조건을 만족한다.
 */
@Component
public class SafetyCommandBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SafetyCommandBroadcaster.class);

    static final String COMMANDS_ALL = "/topic/vehicles/commands";
    static final String EMERGENCY_STOP_SUMMARY = "/topic/vehicles/emergency-stop";

    private final SimpMessagingTemplate messagingTemplate;

    public SafetyCommandBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** 발행 결과(PUBLISHED/PUBLISH_FAILED)를 공통 + 해당 차량 전용 토픽으로 보낸다. */
    public void broadcastPublish(String commandType, VehicleCommandResponse response) {
        boolean published = "PUBLISHED".equals(response.status());
        String eventType = eventType(commandType, published);
        SafetyCommandEvent event = new SafetyCommandEvent(
                eventType, response.vehicleId(), response.commandId(), commandType,
                response.status(), response.resultMessage(), CommunicationTime.nowOffset());
        send(COMMANDS_ALL, event);
        send(COMMANDS_ALL + "/" + response.vehicleId(), event);
    }

    /** 전체 비상정지 요약 이벤트. */
    public void broadcastGlobalSummary(int requested, int published, int failed) {
        GlobalEmergencyStopEvent event = new GlobalEmergencyStopEvent(
                "GLOBAL_ESTOP_PUBLISHED", requested, published, failed, CommunicationTime.nowOffset());
        send(EMERGENCY_STOP_SUMMARY, event);
    }

    private String eventType(String commandType, boolean published) {
        boolean emergency = "EMERGENCY_STOP".equals(commandType);
        if (emergency) {
            return published ? "VEHICLE_ESTOP_PUBLISHED" : "VEHICLE_ESTOP_PUBLISH_FAILED";
        }
        return published ? "VEHICLE_STOP_PUBLISHED" : "VEHICLE_STOP_PUBLISH_FAILED";
    }

    private void send(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.error("Failed to broadcast safety command event: destination={}, error={}",
                    destination, e.getMessage());
        }
    }
}
