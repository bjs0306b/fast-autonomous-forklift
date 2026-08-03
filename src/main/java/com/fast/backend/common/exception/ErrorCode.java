package com.fast.backend.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP Method입니다."),
    JSON_PARSE_ERROR(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    VEHICLE_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 차량입니다."),
    VEHICLE_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 vehicleId입니다."),
    VEHICLE_INACTIVE(HttpStatus.CONFLICT, "비활성 차량에는 명령을 발행할 수 없습니다."),
    INVALID_VEHICLE_ID(HttpStatus.BAD_REQUEST, "vehicleId 형식이 올바르지 않습니다."),

    // 통합 차량 명령(prompt32.md 1장 7~12번). 구 EMBEDDED_COMMAND_* 코드를 대체한다 — 이 도메인은
    // 더 이상 임베디드 전용이 아니라 ROS2 이동 명령까지 함께 다루기 때문이다.
    COMMAND_TYPE_INVALID(HttpStatus.BAD_REQUEST, "알 수 없는 명령입니다."),
    COMMAND_COMBINATION_INVALID(HttpStatus.BAD_REQUEST, "targetSystem과 command 조합이 올바르지 않습니다."),
    COMMAND_DESTINATION_INVALID(HttpStatus.BAD_REQUEST, "destination 값이 올바르지 않습니다."),
    COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 명령입니다."),
    COMMAND_LIMIT_INVALID(HttpStatus.BAD_REQUEST, "limit은 1~200 범위여야 합니다."),

    STATION_MEASUREMENT_INVALID(HttpStatus.BAD_REQUEST, "측정 결과 필수값이 올바르지 않습니다."),
    STATION_MEASUREMENT_STATUS_INVALID(HttpStatus.BAD_REQUEST, "status 값이 올바르지 않거나 상태별 필드 조합이 유효하지 않습니다."),
    STATION_MEASUREMENT_DIMENSIONS_INVALID(HttpStatus.BAD_REQUEST, "dimensions 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 저장된 measurement_id입니다."),
    STATION_MEASUREMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 측정 결과입니다."),

    // ── 측정 세션(prompt96) ──────────────────────────────────────────────────
    STATION_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 측정 세션입니다."),
    STATION_SESSION_NOT_ACTIVE(HttpStatus.CONFLICT, "활성화된 측정 세션이 아닙니다."),
    /** 활성 세션은 있는데 요청이 가리키는 세션과 다르다 — 해제된 옛 세션의 늦은 측정(prompt107). */
    STATION_SESSION_MISMATCH(HttpStatus.CONFLICT, "측정 요청의 세션이 현재 활성 세션과 일치하지 않습니다."),
    STATION_ALREADY_OCCUPIED(HttpStatus.CONFLICT, "측정 설비가 이미 다른 세션에 점유되어 있습니다."),
    STATION_MEASUREMENT_NOT_COMPLETED(HttpStatus.CONFLICT, "측정 결과가 아직 저장되지 않아 세션을 종료할 수 없습니다."),
    /** 같은 세션에 이미 결과가 있다 — measurementId 중복(재전송)과 구분한다(5장). */
    STATION_SESSION_MEASUREMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이 세션에는 이미 측정 결과가 저장되어 있습니다."),

    // ── 적재 추천 안전 게이트(prompt96 11장) ─────────────────────────────────
    // 추천 불가를 빈 목록이나 null 로 뭉개지 않고 원인을 구분해 알린다.
    STATION_MEASUREMENT_STATUS_NOT_ELIGIBLE(HttpStatus.CONFLICT, "측정 상태가 적재 추천 대상이 아닙니다."),
    STATION_MEASUREMENT_HEIGHT_INVALID(HttpStatus.CONFLICT, "화물 높이가 없거나 유효하지 않아 적재 추천을 할 수 없습니다."),
    STATION_TIPPING_LEVEL_NOT_SAFE(HttpStatus.CONFLICT, "전복 위험 등급이 SAFE가 아니어서 적재 추천을 할 수 없습니다."),
    STATION_OVERHANG_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "화물 돌출률이 허용 한계 이상이어서 적재 추천을 할 수 없습니다."),

    // 화물 크기 기반 적재 위치 추천 및 운반 작업 디스패치(prompt46.md). 기존 공통 예외 구조(BusinessException +
    // ErrorCode)를 그대로 쓰고, 도메인별 예외 클래스를 새로 만들지 않는다(이 프로젝트의 기존 관례).
    CARGO_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 화물입니다."),
    CARGO_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 cargoId입니다."),
    CARGO_DIMENSION_INVALID(HttpStatus.BAD_REQUEST, "화물 높이는 0보다 커야 합니다."),
    TASK_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 배정된(또는 PENDING이 아닌) 작업입니다."),
    NO_AVAILABLE_STORAGE_SLOT(HttpStatus.CONFLICT, "화물이 들어갈 수 있는 빈 슬롯이 없습니다."),
    STORAGE_SLOT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 슬롯입니다."),
    TRANSPORT_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 운반 작업입니다."),
    INVALID_TASK_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않는 작업 상태 전이입니다."),
    VEHICLE_NOT_AVAILABLE(HttpStatus.CONFLICT, "배정할 수 없는 차량 상태입니다."),
    VEHICLE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 활성 작업이 배정된 차량입니다.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
