package com.fast.backend.common.time;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 통신 계층(MQTT payload, WebSocket envelope, REST 응답)의 시각 표준을 한 곳에서 관리한다.
 *
 * <p><b>확정 규격(prompt32.md 1장 6번)</b>: 모든 통신 시각은 Asia/Seoul(+09:00) ISO-8601
 * {@link OffsetDateTime}으로 주고받는다(예: {@code 2026-07-23T11:20:27+09:00}).
 *
 * <p><b>DB 저장 방식</b>: MySQL/H2 공용 {@code DATETIME} 컬럼은 타임존을 담지 못한다. 이 프로젝트는
 * 두 가지 선택지 중 <b>"애플리케이션 내부에서 Asia/Seoul 고정 정책으로 변환"</b>(prompt32.md 1장 6번의
 * 두 번째 안)을 택했다. 즉 <b>DB에 저장되는 모든 시각은 Asia/Seoul 기준 벽시계 시각</b>이며, 읽어올 때
 * {@link #toOffset(LocalDateTime)}으로 {@code +09:00}을 다시 붙여 원래 값을 복원한다.
 *
 * <p>스테이션 도메인({@code station_measurement})이 이미 쓰고 있는 "UTC 변환 시각 + offset minutes 분리
 * 저장" 방식을 차량/명령 도메인에 확대 적용하지 않은 이유:
 * <ul>
 *   <li>차량·명령·포크·오류 도메인의 시각 컬럼은 10개가 넘어, 전부 2컬럼으로 쪼개면 기존 Mapper XML·
 *       Domain·테스트가 전면 재작성된다(변경 위험 대비 이득이 없다).</li>
 *   <li>스테이션은 <b>외부 스테이션 PC가 임의의 오프셋을 보낼 수 있다</b>는 전제라 오프셋 자체를 보존해야
 *       했지만, 이번 확정 규격은 <b>오프셋을 +09:00 하나로 고정</b>했다. 고정 오프셋에서는 분리 저장이
 *       추가 정보를 전혀 주지 못한다.</li>
 *   <li>스테이션 코드는 이 클래스를 사용하지 않는다 — 기존 저장·복원 방식을 그대로 둔다
 *       (prompt32.md "관련 없는 스테이션 코드를 깨뜨리지 말 것").</li>
 * </ul>
 *
 * <p>한국 표준시는 서머타임이 없어 오프셋이 항상 {@code +09:00}이다. 그래서 {@link ZoneId} 대신
 * 고정 {@link ZoneOffset}을 써도 계산 결과가 달라지지 않으며, 직렬화 결과가 항상 {@code +09:00}으로
 * 안정적이다.
 */
public final class CommunicationTime {

    /** 확정 규격 오프셋 — 모든 통신 시각의 직렬화 오프셋. */
    public static final ZoneOffset OFFSET = ZoneOffset.ofHours(9);

    /** DB에 저장하는 벽시계 시각의 기준 타임존. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private CommunicationTime() {
    }

    /** 통신 계층에서 사용할 현재 시각(+09:00). */
    public static OffsetDateTime nowOffset() {
        return OffsetDateTime.now(OFFSET);
    }

    /** DB에 저장할 현재 시각(Asia/Seoul 벽시계). */
    public static LocalDateTime nowLocal() {
        return LocalDateTime.now(ZONE);
    }

    /**
     * 통신으로 받은 {@link OffsetDateTime}을 DB 저장용 Asia/Seoul 벽시계 시각으로 변환한다.
     * 송신 측이 {@code +00:00}처럼 다른 오프셋을 보내더라도 <b>같은 순간(instant)</b>을 가리키는
     * Asia/Seoul 시각으로 정확히 환산된다(문자열을 그대로 잘라내지 않는다).
     */
    public static LocalDateTime toLocal(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZONE).toLocalDateTime();
    }

    /**
     * DB에서 읽은 Asia/Seoul 벽시계 시각에 {@code +09:00}을 붙여 통신용 {@link OffsetDateTime}으로
     * 복원한다.
     */
    public static OffsetDateTime toOffset(LocalDateTime value) {
        return value == null ? null : value.atOffset(OFFSET);
    }
}
