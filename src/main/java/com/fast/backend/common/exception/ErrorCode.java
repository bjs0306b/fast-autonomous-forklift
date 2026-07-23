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

    EMBEDDED_COMMAND_TYPE_INVALID(HttpStatus.BAD_REQUEST, "알 수 없는 명령입니다."),
    EMBEDDED_COMMAND_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 존재하는 commandId입니다."),
    EMBEDDED_COMMAND_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 명령입니다."),
    EMBEDDED_COMMAND_LIMIT_INVALID(HttpStatus.BAD_REQUEST, "limit은 1~200 범위여야 합니다."),
    EMBEDDED_FORK_STATUS_NOT_FOUND(HttpStatus.NOT_FOUND, "포크 상태 정보가 없습니다."),

    STATION_MEASUREMENT_SCHEMA_VERSION_UNSUPPORTED(HttpStatus.BAD_REQUEST, "지원하지 않는 schema_version입니다."),
    STATION_MEASUREMENT_INVALID(HttpStatus.BAD_REQUEST, "측정 결과 필수값이 올바르지 않습니다."),
    STATION_MEASUREMENT_STATUS_INVALID(HttpStatus.BAD_REQUEST, "status 값이 올바르지 않거나 상태별 필드 조합이 유효하지 않습니다."),
    STATION_MEASUREMENT_DETECTION_INVALID(HttpStatus.BAD_REQUEST, "detection 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_DISTANCE_INVALID(HttpStatus.BAD_REQUEST, "distance 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_DIMENSIONS_INVALID(HttpStatus.BAD_REQUEST, "dimensions 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_LOAD_BALANCE_INVALID(HttpStatus.BAD_REQUEST, "load_balance 값이 올바르지 않습니다."),
    STATION_MEASUREMENT_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 저장된 measurement_id입니다."),
    STATION_MEASUREMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 측정 결과입니다.");

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
