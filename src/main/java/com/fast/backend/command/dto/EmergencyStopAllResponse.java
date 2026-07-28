package com.fast.backend.command.dto;

import java.util.List;

/**
 * 전체 비상정지(emergency-stop-all) 요약 응답(prompt53.md 9장). 차량별 개별 결과를 담아 부분 성공을 표현한다.
 * 한 차량 발행 실패가 다른 차량 발행을 막지 않으며, 실패는 {@code failureReason}으로 드러낸다.
 */
public record EmergencyStopAllResponse(
        int requestedCount,
        int publishedCount,
        int failedCount,
        List<Item> results
) {
    public record Item(
            String vehicleId,
            String commandId,
            String command,
            String status,
            String failureReason
    ) {
    }
}
