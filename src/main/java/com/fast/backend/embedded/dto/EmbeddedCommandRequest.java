package com.fast.backend.embedded.dto;

/** {@code POST /api/vehicles/{forkliftId}/embedded-commands} 요청 바디(prompt29.md 18장). */
public record EmbeddedCommandRequest(String command, String reason) {
}
