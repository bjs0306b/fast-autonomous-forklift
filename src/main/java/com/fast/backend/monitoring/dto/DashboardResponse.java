package com.fast.backend.monitoring.dto;

import com.fast.backend.vehicle.domain.VehicleStatus;
import java.time.OffsetDateTime;
import java.util.List;

/** 관제 대시보드 초기화에 사용하는 상태 스냅샷. */
public record DashboardResponse(List<VehicleView> vehicles, List<TaskView> tasks) {

    /**
     * @param hasCargo    화물 적재 여부. 확인할 근거가 없으면 {@code null}(= 화면에서 "확인 불가").
     *                    {@code false} 와 {@code null} 을 구분하려고 원시 타입을 쓰지 않는다.
     * @param cargoId     차량이 싣고 있는(또는 실을) 화물 식별자. 없으면 {@code null}.
     * @param cargoHeight {@code cargoId} 화물의 측정 높이(m, 팔레트 제외). 측정 결과가 없으면 {@code null}.
     */
    public record VehicleView(
            String vehicleId,
            String name,
            boolean active,
            VehicleStatus status,
            LocationView location,
            CurrentTaskView currentTask,
            Boolean hasCargo,
            Long cargoId,
            Double cargoHeight,
            FailureView lastFailure,
            OffsetDateTime lastUpdatedAt) {
    }

    /**
     * 이 차량의 가장 최근 실패 작업. 실패가 없거나 그 뒤로 새 작업이 시작됐으면 {@code null} 이다.
     *
     * <p><b>문구는 담지 않는다.</b> 백엔드는 원인 코드와 원본 측정값만 내려주고 사용자 문구는 프론트가
     * 한 곳에서 매핑한다 — 같은 의미를 두 벌로 관리하지 않기 위해서다.
     *
     * @param failureCode       {@link com.fast.backend.transport.domain.TaskFailureCode} 이름
     * @param measurementStatus 원본 측정 상태({@code ok}/{@code dimensions_only}/…). 결과가 아예
     *                          도착하지 않은 무응답 실패에서는 {@code null}.
     * @param placementEligible 적재 적합 여부. 측정 결과가 없으면 {@code null} — {@code false}(부적합)와
     *                          "판정한 적 없음"을 구분한다.
     * @param overhangRatio     팔레트 폭 대비 돌출 비율(무차원). 판정 불가 상태면 {@code null}.
     * @param tippingLevel      전복 위험 등급({@code SAFE}/{@code WARNING}/{@code DANGER}). 없으면 {@code null}.
     */
    public record FailureView(
            String taskId,
            String failureCode,
            String measurementStatus,
            Boolean placementEligible,
            Double overhangRatio,
            String tippingLevel,
            OffsetDateTime occurredAt) {
    }

    public record LocationView(
            Double x,
            Double y,
            Double heading,
            Double speed,
            String frameId,
            OffsetDateTime messageAt,
            OffsetDateTime receivedAt) {
    }

    public record CurrentTaskView(String taskId, String status, OffsetDateTime updatedAt) {
    }

    /** @param failureCode 실패 작업일 때만 채워진다. 그 외에는 {@code null}. */
    public record TaskView(String taskId, String vehicleId, String status, String failureCode,
            OffsetDateTime updatedAt) {
    }
}
