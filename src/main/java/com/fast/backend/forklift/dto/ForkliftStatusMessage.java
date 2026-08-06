package com.fast.backend.forklift.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.OffsetDateTime;

/**
 * forklift/{forkliftId}/status 토픽으로 수신되는 지게차 상태 메시지.
 *
 * <p><b>{@code @JsonIgnoreProperties(ignoreUnknown = true)}가 이 계약의 핵심이다.</b> 백엔드는
 * {@code battery}를 더 이상 사용하지 않지만, ROS2·Isaac Sim 송신자는 이번 변경 범위 밖이라 기존처럼
 * {@code battery}를 계속 실어 보낸다. 이 애너테이션이 없으면 Jackson 설정에 따라 알 수 없는 필드에서
 * 역직렬화가 실패할 수 있고, 그러면 구형 송신자의 상태 메시지가 통째로 폐기된다.
 *
 * <p>Spring Boot 기본값도 {@code FAIL_ON_UNKNOWN_PROPERTIES=false}지만 그 기본값에 기대지 않고
 * <b>DTO에 명시</b>한다 — 이 무시 동작이 외부 송신자와의 호환 계약 그 자체라, 전역 설정이 바뀌어도
 * 이 메시지는 계속 안전하게 파싱돼야 하기 때문이다.
 *
 * <p>{@code hasCargo}/{@code cargoId}는 새 ROS2 송신자가 실제 적재 센서값을 보고할 때 사용한다.
 * 과거 송신자가 두 필드를 생략하면 null로 들어와 DB의 기존 적재 값을 보존한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForkliftStatusMessage(
        String forkliftId,
        String status,
        OffsetDateTime timestamp,
        @JsonAlias("loaded") Boolean hasCargo,
        Long cargoId
) {
    /** 적재 필드가 없던 기존 ROS2 송신자와 테스트의 소스 호환용. */
    public ForkliftStatusMessage(String forkliftId, String status, OffsetDateTime timestamp) {
        this(forkliftId, status, timestamp, null, null);
    }
}
