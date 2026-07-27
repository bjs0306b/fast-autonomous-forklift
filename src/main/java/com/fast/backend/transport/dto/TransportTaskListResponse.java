package com.fast.backend.transport.dto;

import java.util.List;

/**
 * 운반 작업 목록 응답(prompt47.md 10장). 프로젝트에 공통 페이지네이션 프레임워크가 없어(확인함),
 * 과한 공통 구조를 새로 만들지 않고 목록 응답에 필요한 최소 페이징 메타만 포함한다.
 */
public record TransportTaskListResponse(
        List<TransportTaskResponse> items,
        int page,
        int size,
        long totalElements
) {
}
