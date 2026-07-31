package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 대시보드 초기 조회 통합 응답(prompt50.md 10장, prompt56.md 6~9장 확장). vehicleId 기준으로 차량 기본 정보 +
 * 현재 상태 + 최신 위치 + 현재 진행 작업을 결합하고, 최신 운반 작업 상태 목록을 함께 담는다.
 * 위치가 없는 차량도 목록에서 사라지지 않으며 location은 nullable이다.
 *
 * <p><b>관제 프론트 초기 조회 1회로 끝내기 위한 확장(prompt56.md)</b>: 이전에는 차량명·source를 얻으려면
 * {@code GET /api/vehicles}를, 현재 작업을 얻으려면 {@code tasks} 100건을 프론트가 직접 필터링해야 했다.
 * 그 두 가지를 이 응답 안에서 해결한다:
 * <ul>
 *   <li>{@link VehicleView#name()} — 차량 기본 정보(source 는 FR-202 에서 제거됨, prompt90)</li>
 *   <li>{@link LocationView#messageAt()} / {@link LocationView#receivedAt()} / {@link LocationView#source()}
 *       — 위치 신선도 판단과 REAL/SIM 위치 출처 구분</li>
 *   <li>{@link VehicleView#currentTask()} — 차량별 진행 중 작업 요약(종료 상태 제외)</li>
 * </ul>
 *
 * <p><b>하위 호환</b>: 기존 필드({@code vehicleId}/{@code status}/{@code location}의 x·y·heading·speed·frameId/
 * {@code lastUpdatedAt}, {@code tasks} 배열 전체)는 이름·타입·의미를 그대로 유지한다. 추가만 했고 삭제·개명은 없다.
 *
 * <p><b>battery는 포함하지 않는다</b> — 관제 화면 요구사항에서 제외됐고, 이 응답은 처음부터 battery를 담지
 * 않았다(prompt56.md 7·8장).
 */
public record DashboardResponse(
        List<VehicleView> vehicles,
        List<TaskView> tasks
) {

    /**
     * 차량 1대의 대시보드 요약.
     *
     * <p><b>{@code active}를 포함하는 이유(prompt57.md 6·7장)</b>: 이 응답은 현재 {@code vehicle.active = true}인
     * 차량만 반환하므로 값이 항상 {@code true}다. 그럼에도 담는 이유는 <b>프론트 계약을 명시적으로 만들기
     * 위해서</b>다 — 프론트가 "이 목록은 활성 차량만"이라는 사실을 문서가 아니라 응답 자체로 확인할 수 있고,
     * 나중에 비활성 차량까지 조회하는 필터가 생겨도 응답 스키마와 프론트 타입이 그대로 유지된다.
     * 제공 비용은 0이다({@code VehicleResponse}가 이미 갖고 있는 값이라 추가 쿼리가 없다).
     */
    public record VehicleView(
            String vehicleId,
            String name,
            boolean active,
            VehicleStatus status,
            LocationView location,
            CurrentTaskView currentTask,
            OffsetDateTime lastUpdatedAt
    ) {
    }

    /**
     * 최신 위치 스냅샷 요약. {@code source}는 <b>위치 메시지 출처 태그</b>({@code "REAL"}/{@code "SIM"})이며
     * 차량 등록 정보인 {@link VehicleView#source()}({@code REAL}/{@code SIMULATION})와는 값 집합이 다르다 —
     * 두 값을 통일하지 않고 각각 원본 그대로 노출한다(prompt56.md 8장).
     */
    public record LocationView(
            Double x,
            Double y,
            Double heading,
            Double speed,
            String frameId,
            OffsetDateTime messageAt,
            OffsetDateTime receivedAt,
            String source
    ) {
    }

    /**
     * 차량이 지금 수행 중인 작업 요약. 종료 상태(COMPLETED/FAILED/CANCELLED)는 제외하며, 진행 중 작업이
     * 없으면 {@link VehicleView#currentTask()}가 null이다. {@code taskId}는 {@code task_code}다.
     */
    public record CurrentTaskView(
            String taskId,
            String status,
            String commandStatus,
            OffsetDateTime updatedAt
    ) {
    }

    public record TaskView(
            String taskId,
            String vehicleId,
            String status,
            String commandStatus,
            OffsetDateTime updatedAt
    ) {
    }
}
