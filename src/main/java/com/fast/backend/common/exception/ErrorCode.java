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
    VEHICLE_BATTERY_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "battery는 0~100 범위여야 합니다."),
    VEHICLE_STATUS_HISTORY_LIMIT_INVALID(HttpStatus.BAD_REQUEST, "limit은 1~200 범위여야 합니다."),

    AI_ANALYSIS_SCHEMA_VERSION_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 schemaVersion입니다."),
    AI_ANALYSIS_STATUS_INVALID(HttpStatus.BAD_REQUEST, "status 값이 올바르지 않거나 상태별 필드 조합이 유효하지 않습니다."),
    AI_ANALYSIS_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 저장된 analysisId입니다."),
    AI_ANALYSIS_BBOX_INVALID(HttpStatus.BAD_REQUEST, "detection.boxes 값이 올바르지 않습니다."),
    AI_ANALYSIS_DISTANCE_INVALID(HttpStatus.BAD_REQUEST, "distance 값이 올바르지 않습니다."),
    AI_ANALYSIS_DIMENSIONS_INVALID(HttpStatus.BAD_REQUEST, "dimensions 값이 올바르지 않습니다."),
    AI_ANALYSIS_LOAD_BALANCE_INVALID(HttpStatus.BAD_REQUEST, "loadBalance.direction 값이 올바르지 않습니다."),
    AI_ANALYSIS_RATIO_INVALID(HttpStatus.BAD_REQUEST, "ratios 값이 올바르지 않습니다."),
    AI_ANALYSIS_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 분석 결과입니다."),

    // 통합 차량 명령(prompt32.md 1장 7~12번). 구 EMBEDDED_COMMAND_* 코드를 대체한다 — 이 도메인은
    // 더 이상 임베디드 전용이 아니라 ROS2 이동 명령까지 함께 다루기 때문이다.
    COMMAND_TYPE_INVALID(HttpStatus.BAD_REQUEST, "알 수 없는 명령입니다."),
    COMMAND_COMBINATION_INVALID(HttpStatus.BAD_REQUEST, "targetSystem/commandCategory 조합이 올바르지 않습니다."),
    COMMAND_DESTINATION_INVALID(HttpStatus.BAD_REQUEST, "destination 값이 올바르지 않습니다."),
    COMMAND_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 commandId입니다."),
    COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 명령입니다."),
    COMMAND_LIMIT_INVALID(HttpStatus.BAD_REQUEST, "limit은 1~200 범위여야 합니다."),

    EMBEDDED_FORK_STATUS_NOT_FOUND(HttpStatus.NOT_FOUND, "포크 상태 정보가 없습니다."),

    STATION_MEASUREMENT_SCHEMA_VERSION_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 schema_version입니다."),
    STATION_MEASUREMENT_INVALID(HttpStatus.BAD_REQUEST, "측정 결과 필수값이 올바르지 않습니다."),
    STATION_MEASUREMENT_STATUS_INVALID(HttpStatus.BAD_REQUEST, "status 값이 올바르지 않거나 상태별 필드 조합이 유효하지 않습니다."),
    STATION_MEASUREMENT_DETECTION_INVALID(HttpStatus.BAD_REQUEST, "detection 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_DISTANCE_INVALID(HttpStatus.BAD_REQUEST, "distance 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_DIMENSIONS_INVALID(HttpStatus.BAD_REQUEST, "dimensions 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_LOAD_BALANCE_INVALID(HttpStatus.BAD_REQUEST, "load_balance 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 저장된 measurement_id입니다."),
    STATION_MEASUREMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 측정 결과입니다."),

    // 화물 크기 기반 적재 위치 추천 및 운반 작업 디스패치(prompt46.md). 기존 공통 예외 구조(BusinessException +
    // ErrorCode)를 그대로 쓰고, 도메인별 예외 클래스를 새로 만들지 않는다(이 프로젝트의 기존 관례).
    CARGO_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 화물입니다."),
    CARGO_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 cargoId입니다."),
    CARGO_DIMENSION_INVALID(HttpStatus.BAD_REQUEST, "화물 width/length/height는 0보다 커야 합니다."),
    PALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 팔레트입니다."),
    PALLET_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 palletId입니다."),
    PALLET_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 다른 작업에 배정된 팔레트입니다."),
    PALLET_CARGO_MISMATCH(HttpStatus.BAD_REQUEST, "팔레트에 연결된 화물과 요청 화물이 일치하지 않습니다."),
    TASK_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 배정된(또는 PENDING이 아닌) 작업입니다."),
    RACK_NOT_FOUND(HttpStatus.NOT_FOUND, "등록되지 않은 선반입니다."),
    STORAGE_SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 슬롯입니다."),
    NO_AVAILABLE_STORAGE_SLOT(HttpStatus.CONFLICT, "화물이 들어갈 수 있는 빈 슬롯이 없습니다."),
    STORAGE_SLOT_ALREADY_RESERVED(HttpStatus.CONFLICT, "이미 예약된 슬롯입니다."),
    TRANSPORT_TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 운반 작업입니다."),
    INVALID_TASK_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않는 작업 상태 전이입니다."),
    VEHICLE_NOT_AVAILABLE(HttpStatus.CONFLICT, "배정할 수 없는 차량 상태입니다."),
    VEHICLE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "이미 활성 작업이 배정된 차량입니다."),
    NO_AVAILABLE_VEHICLE(HttpStatus.CONFLICT, "배정 가능한 차량이 없습니다."),
    TASK_ALREADY_DISPATCHED(HttpStatus.CONFLICT, "이미 디스패치된 작업입니다."),
    MQTT_DISPATCH_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "운반 명령 디스패치에 실패했습니다."),
    TASK_NOT_ASSIGNED(HttpStatus.CONFLICT, "ASSIGNED 상태의 작업만 디스패치할 수 있습니다."),
    TRANSPORT_COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 운반 명령입니다."),
    COMMAND_RESULT_VEHICLE_MISMATCH(HttpStatus.BAD_REQUEST, "명령 결과의 vehicleId가 발행 기록과 일치하지 않습니다."),
    INVALID_TRANSPORT_COMMAND_STATUS(HttpStatus.CONFLICT, "허용되지 않는 운반 명령 상태 전이입니다.");

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
