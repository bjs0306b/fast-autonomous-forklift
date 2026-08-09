package com.fast.backend.traffic.dto;

import com.fast.backend.traffic.domain.OperationState;
import com.fast.backend.traffic.service.OperationService;

import java.util.List;

/**
 * 운행 상태 응답.
 *
 * <p><b>버튼 활성 여부를 백엔드가 계산해서 준다.</b> 프론트가 {@code state} 문자열을 보고
 * 직접 분기하면 상태가 늘어날 때마다 양쪽을 고쳐야 하고, 한쪽만 고치면 "눌리는데 400 이 뜨는"
 * 버튼이 생긴다. 전이 규칙은 {@link OperationState} 한 곳에만 둔다.
 *
 * @param state          현재 운행 상태
 * @param canStart       [운행 시작] 활성 여부
 * @param canResume      [재개] 활성 여부
 * @param joinedCount    순환로에 합류한 차량 수 — 화면의 "합류 중 (1/3)" 분자
 * @param joinTargetCount 합류 대상(온라인 제어 대상) 수 — 분모
 * @param joinedVehicles 합류한 차량 ID
 * @param heldVehicles   사용자가 개별로 세워 둔 차량 ID
 * @param autoRelease    규칙 0 자동 합류가 도는가. [운행 시작] 이면 true,
 *                       차량 개별 출발이면 false — 화면의 "합류 중" 표시가 이 값을 본다
 */
public record OperationStateResponse(
        OperationState state,
        boolean canStart,
        boolean canResume,
        int joinedCount,
        int joinTargetCount,
        List<String> joinedVehicles,
        List<String> heldVehicles,
        boolean autoRelease
) {

    @SuppressWarnings("unchecked")
    public static OperationStateResponse from(OperationService service) {
        var snapshot = service.snapshot();
        OperationState state = service.state();
        return new OperationStateResponse(
                state,
                state.canStart(),
                state.canResume(),
                service.joinedCount(),
                service.joinTargetCount(),
                (List<String>) snapshot.getOrDefault("joined", List.of()),
                (List<String>) snapshot.getOrDefault("manuallyHeld", List.of()),
                service.isAutoRelease());
    }
}
