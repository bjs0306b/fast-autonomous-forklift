package com.fast.backend.transport.dto;

import jakarta.validation.constraints.NotBlank;

/** PATCH /api/transport-tasks/{taskId}/assign 요청(prompt47.md 9장). */
public record TransportTaskAssignRequest(
        @NotBlank String vehicleId
) {
}
