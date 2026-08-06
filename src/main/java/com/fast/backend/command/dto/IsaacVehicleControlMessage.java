package com.fast.backend.command.dto;

/**
 * Isaac Sim의 {@code fast/v1/vehicle/{id}/control} 및 {@code fast/v1/control/all} 계약.
 * command는 ESTOP/RESUME/HOLD/CANCEL_TASK 중 하나다.
 */
public record IsaacVehicleControlMessage(String command) {
}
