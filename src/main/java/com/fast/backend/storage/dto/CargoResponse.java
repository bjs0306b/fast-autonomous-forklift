package com.fast.backend.storage.dto;

import com.fast.backend.storage.domain.Cargo;
import com.fast.backend.transport.domain.TaskStatus;
import com.fast.backend.transport.dto.TransportTaskResponse;
import java.time.LocalDateTime;

public record CargoResponse(
        Long cargoId,
        LocalDateTime createdAt,
        String taskId,
        TaskStatus taskStatus) {
    public static CargoResponse from(Cargo cargo, TransportTaskResponse task) {
        return new CargoResponse(cargo.getCargoId(), cargo.getCreatedAt(), task.taskId(), task.status());
    }
}
