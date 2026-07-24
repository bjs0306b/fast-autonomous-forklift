package com.fast.backend.isaac.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.OffsetDateTime;

/**
 * {@code forklift/{id}/location} 토픽으로 수신되는 Isaac Sim 위치 메시지.
 *
 * <p>같은 토픽 이름을 실물 ROS2 위치 메시지({@code ForkliftLocationMessage}, {@code vehicleId} 키·중첩
 * {@code position} 구조)가 함께 사용한다. {@code MqttMessageRouter}는 payload에 {@code forkliftId} 키가
 * 있으면 이 DTO(Isaac 경로)로, {@code vehicleId} 키면 기존 ROS2 경로로 구분해 역직렬화한다 —
 * <b>이 판별 구조는 prompt32.md 1장 2번 확정에 따라 그대로 유지</b>한다(상태·위치 메시지의 식별자 키는
 * 임의로 통일하지 않는다).
 *
 * <p><b>단위 규격이 확정됐다(prompt32.md 1장 5번)</b>
 * <ul>
 *   <li>{@code x}/{@code y} — <b>m</b>. Isaac과 ROS2는 <b>동일한 원점</b>을 사용한다고 가정한다.</li>
 *   <li>{@code heading} — <b>degree</b>, 정상 범위 [0,360). 과거에는 이 필드 이름이 {@code direction}이고
 *       단위가 rad였다. 확정 규격에 따라 표준 필드명을 {@code heading}, 단위를 degree로 바꿨다.</li>
 *   <li>{@code speed} — m/s</li>
 * </ul>
 *
 * <p><b>{@code direction} alias(과도기 호환, prompt32.md 1장 5번)</b>: Isaac 브리지가 아직 옛 필드
 * 이름으로 발행할 수 있어 {@link JsonAlias}로 <b>읽기만</b> 허용한다. 백엔드가 WebSocket으로 내보낼 때는
 * 항상 {@code heading}이다. 브리지가 전환된 것이 확인되면 이 alias를 제거한다.
 *
 * <p><b>주의(외부 연동 확인 필요)</b>: alias는 <b>이름</b>만 호환할 뿐 <b>단위</b>를 변환하지 않는다.
 * 옛 브리지가 rad 값을 {@code direction}으로 보내면 그 값이 degree로 해석된다. 단위 전환은 Isaac 브리지
 * 쪽에서 함께 이뤄져야 하며, 이는 저장소만으로 검증할 수 없다.
 */
public record IsaacForkliftLocationMessage(
        String forkliftId,
        Double x,
        Double y,
        @JsonAlias("direction") Double heading,
        Double speed,
        OffsetDateTime timestamp
) {
}
