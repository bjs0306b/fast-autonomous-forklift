package com.fast.backend.traffic.service;

import com.fast.backend.command.dto.IsaacVehicleTaskMessage;
import com.fast.backend.command.service.VehicleCommandPublisher;
import com.fast.backend.config.mqtt.MqttTopics;
import com.fast.backend.mqtt.outbound.MqttPublisher;
import com.fast.backend.traffic.domain.Angles;
import com.fast.backend.traffic.dto.CargoActionMessage;
import com.fast.backend.traffic.dto.PlaceRackTaskMessage;
import com.fast.backend.vehicle.service.VehicleIdAliasResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 주기 상태기계가 쓰는 발행 경계 — {@code task} 와 {@code cargo} 를 MQTT 로 내보낸다.
 *
 * <p><b>왜 서비스에서 떼어 냈는가.</b> 주기 로직은 "언제 무엇을 보낼지"를 정하는 일이고,
 * 여기는 "어느 토픽에 어떤 모양으로 보낼지"를 안다. 섞어 두면 주기 로직을 테스트할 때마다
 * MQTT 를 흉내 내야 한다.
 *
 * <p><b>발행 실패를 예외로 올리지 않는다.</b> 한 차량 발행 실패가 tick 을 중단시키면 나머지
 * 차량의 판단까지 멈춘다. 대신 로그로 남기고 {@code false} 를 돌려주어, 호출부가 "보냈다"고
 * 기억하지 않게 한다 — 다음 tick 에 다시 시도된다.
 */
@Component
public class CycleGoalPublisher {

    private static final Logger log = LoggerFactory.getLogger(CycleGoalPublisher.class);

    private final MqttPublisher mqttPublisher;
    private final MqttTopics mqttTopics;
    private final VehicleIdAliasResolver aliasResolver;

    /** taskId 일련번호. 차량이 "같은 목표를 또 받았는지" 구분하는 데 쓴다. */
    private final AtomicLong taskSequence = new AtomicLong(1);

    public CycleGoalPublisher(
            MqttPublisher mqttPublisher,
            MqttTopics mqttTopics,
            VehicleIdAliasResolver aliasResolver) {
        this.mqttPublisher = mqttPublisher;
        this.mqttTopics = mqttTopics;
        this.aliasResolver = aliasResolver;
    }

    /**
     * 목적지 지시.
     *
     * @param yawRad 진입 방향(<b>라디안</b>). 규격이 라디안으로 준다
     * @return 발행 성공 여부
     */
    public boolean publishGoal(String vehicleId, double x, double y, double yawRad) {
        String externalId = external(vehicleId);
        if (externalId == null) {
            return false;
        }
        String taskId = "T-" + taskSequence.getAndIncrement();
        // 규격은 yaw 를 −π~+π 로 정의한다. 발행 경계에서 한 번 접어 두면 계산 쪽이
        // degree 로 다루든 라디안으로 다루든 범위 밖 값이 나가지 않는다.
        double yaw = Angles.normalizeRadians(yawRad);
        try {
            mqttPublisher.publish(
                    new IsaacVehicleTaskMessage(taskId, x, y, yaw),
                    mqttTopics.isaacVehicleTask(externalId),
                    VehicleCommandPublisher.COMMAND_QOS,
                    VehicleCommandPublisher.COMMAND_RETAINED);
            log.info("주기 목표 발행: vehicleId={}, taskId={}, x={}, y={}", vehicleId, taskId, x, y);
            return true;
        } catch (RuntimeException e) {
            log.error("주기 목표 발행 실패: vehicleId={}, error={}", vehicleId, e.getMessage());
            return false;
        }
    }

    /**
     * 랙 적재 지시 (규격 §7). <b>시뮬·실물 공통 페이로드</b>라 한 번만 보내면 양쪽 다 동작한다.
     *
     * <p>{@code cargo} 의 {@code place_rack} 과 달리 좌표를 담는다 — 실물은 랙 이름을 모르고
     * 좌표로만 움직인다.
     */
    public boolean publishPlaceRack(
            String vehicleId, String rack, String cargoId,
            PlaceRackTaskMessage.Waypoint approach, PlaceRackTaskMessage.Waypoint dock,
            double shelfHeight, double reverseDist) {

        String externalId = external(vehicleId);
        if (externalId == null) {
            return false;
        }
        String taskId = "T-" + taskSequence.getAndIncrement();
        try {
            mqttPublisher.publish(
                    PlaceRackTaskMessage.of(
                            taskId, rack, cargoId,
                            normalize(approach), normalize(dock), shelfHeight, reverseDist),
                    mqttTopics.isaacVehicleTask(externalId),
                    VehicleCommandPublisher.COMMAND_QOS,
                    VehicleCommandPublisher.COMMAND_RETAINED);
            log.info("랙 적재 지시 발행: vehicleId={}, taskId={}, rack={}", vehicleId, taskId, rack);
            return true;
        } catch (RuntimeException e) {
            log.error("랙 적재 지시 발행 실패: vehicleId={}, rack={}, error={}",
                    vehicleId, rack, e.getMessage());
            return false;
        }
    }

    /** 화물 지시({@code align_bay} / 화물 받기 / {@code place_rack} / {@code drop}). */
    public boolean publishCargo(String vehicleId, CargoActionMessage message) {
        String externalId = external(vehicleId);
        if (externalId == null) {
            return false;
        }
        try {
            mqttPublisher.publish(
                    message,
                    mqttTopics.isaacVehicleCargo(externalId),
                    VehicleCommandPublisher.COMMAND_QOS,
                    VehicleCommandPublisher.COMMAND_RETAINED);
            log.info("주기 화물 지시 발행: vehicleId={}, action={}, rack={}",
                    vehicleId, message.action(), message.rack());
            return true;
        } catch (RuntimeException e) {
            log.error("주기 화물 지시 발행 실패: vehicleId={}, error={}", vehicleId, e.getMessage());
            return false;
        }
    }

    /** 웨이포인트의 yaw 도 같은 규칙으로 접는다. */
    private static PlaceRackTaskMessage.Waypoint normalize(PlaceRackTaskMessage.Waypoint w) {
        return w == null ? null
                : new PlaceRackTaskMessage.Waypoint(w.x(), w.y(), Angles.normalizeRadians(w.yaw()));
    }

    /**
     * DB 기준 ID 를 브로커가 쓰는 외부 ID 로 바꾼다.
     *
     * <p>못 바꾸면 발행하지 않는다 — 엉뚱한 토픽으로 나가느니 안 보내는 편이 낫다.
     */
    private String external(String vehicleId) {
        return aliasResolver.resolveExternal(vehicleId)
                .filter(id -> !id.isBlank())
                .orElseGet(() -> {
                    log.warn("외부 차량 ID 를 찾지 못해 발행을 건너뜀: vehicleId={}", vehicleId);
                    return null;
                });
    }
}
