package com.fast.backend.transport.dto;

import jakarta.validation.constraints.NotBlank;

/** PATCH /api/transport-tasks/{taskId}/status 요청(prompt47.md 11장). status는 TaskStatus 이름. */
public record TransportTaskStatusUpdateRequest(
        @NotBlank String status
) {
}
