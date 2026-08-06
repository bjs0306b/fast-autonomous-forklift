package com.fast.backend.traffic.domain;

import java.time.LocalDateTime;

/**
 * 관제가 차량을 세우거나 재개시킨 사건 하나 (FR-502-1a).
 *
 * <p><b>왜 별도 테이블인가</b>: 발행된 명령 자체는 {@code vehicle_command} 에 이미 남는다. 하지만
 * 거기엔 "왜 그렇게 판단했는지"를 담을 자리가 없고({@code result_message} 는 <b>명령 처리 결과</b>라
 * 축이 다르다), 안전거리·구역·상대 차량 같은 근거 값도 들어가지 않는다. 사고 후 "그때 왜 멈췄나"를
 * 되짚으려면 그 근거가 남아 있어야 한다.
 *
 * <p>MyBatis 매핑 편의를 위해 record 가 아니라 setter 가 있는 POJO 로 둔다
 * (같은 이유로 {@code StorageSlot}·{@code VehicleCurrentStatus} 도 POJO 다).
 */
public class TrafficControlEvent {

    private Long id;
    private String vehicleId;
    private TrafficEventType eventType;
    /** 정지 사유. {@link TrafficEventType#RELEASE} 이면 <b>해제된 직전 사유</b>를 남긴다. */
    private TrafficHoldReason reasonCode;
    private String reasonDetail;
    private String counterpartVehicleId;
    private String slotCode;
    private Double distanceM;
    /** 이 판단으로 실제 발행된 명령. 발행에 실패했으면 {@code null}. */
    private String commandId;
    private LocalDateTime occurredAt;

    public static TrafficControlEvent hold(
            String vehicleId, TrafficStopDecision decision, LocalDateTime occurredAt) {
        TrafficControlEvent event = new TrafficControlEvent();
        event.vehicleId = vehicleId;
        event.eventType = TrafficEventType.HOLD;
        event.reasonCode = decision.reason();
        event.reasonDetail = decision.detail();
        event.counterpartVehicleId = decision.counterpartVehicleId();
        event.slotCode = decision.slotCode();
        event.distanceM = decision.distanceM();
        event.occurredAt = occurredAt;
        return event;
    }

    /**
     * 재개 이벤트. <b>직전 정지 사유를 함께 남긴다</b> — "무엇이 풀려서 다시 갔는지"를 알아야
     * 한 쌍으로 읽힌다. 해제 사유를 따로 두지 않는 이유는 조건이 항상 같기 때문이다
     * (구역 이탈 + 안전거리 확보).
     */
    public static TrafficControlEvent release(
            String vehicleId, TrafficStopDecision previous, LocalDateTime occurredAt) {
        TrafficControlEvent event = new TrafficControlEvent();
        event.vehicleId = vehicleId;
        event.eventType = TrafficEventType.RELEASE;
        if (previous != null) {
            event.reasonCode = previous.reason();
            event.reasonDetail = "해제됨(직전 사유: %s)".formatted(previous.detail());
            event.counterpartVehicleId = previous.counterpartVehicleId();
            event.slotCode = previous.slotCode();
        }
        event.occurredAt = occurredAt;
        return event;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getVehicleId() { return vehicleId; }
    public void setVehicleId(String vehicleId) { this.vehicleId = vehicleId; }
    public TrafficEventType getEventType() { return eventType; }
    public void setEventType(TrafficEventType eventType) { this.eventType = eventType; }
    public TrafficHoldReason getReasonCode() { return reasonCode; }
    public void setReasonCode(TrafficHoldReason reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public String getCounterpartVehicleId() { return counterpartVehicleId; }
    public void setCounterpartVehicleId(String v) { this.counterpartVehicleId = v; }
    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    public Double getDistanceM() { return distanceM; }
    public void setDistanceM(Double distanceM) { this.distanceM = distanceM; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
}
