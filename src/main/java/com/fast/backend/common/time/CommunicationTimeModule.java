package com.fast.backend.common.time;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 통신 계층 {@link OffsetDateTime} 역직렬화에 <b>과도기 하위 호환</b>을 더하는 Jackson 모듈
 * (prompt32.md 3장 5번 "기존 외부 담당자가 아직 이전 JSON을 사용하고 있을 가능성이 있으므로 가능하면
 * 읽기 호환 전략을 제공한다").
 *
 * <p><b>문제</b>: 확정 규격(1장 6번)은 모든 통신 시각을 {@code 2026-07-23T11:20:27+09:00} 형태로
 * 정했다. 그런데 ROS2·Isaac·임베디드·AI 발행 측이 아직 오프셋 없는 {@code 2026-07-23T11:20:27}을 보낼
 * 수 있다. 표준 Jackson 설정만으로는 이런 payload가 역직렬화 단계에서 통째로 실패해, 메시지가 조용히
 * 폐기되고 원인은 파싱 오류 로그로만 남는다 — 규격 전환 기간에 가장 위험한 실패 방식이다.
 *
 * <p><b>동작</b>
 * <ol>
 *   <li>오프셋이 있으면 그대로 파싱한다(정상 경로). {@code +00:00}처럼 다른 오프셋도 그대로 보존되며,
 *       DB 저장 시점에 {@link CommunicationTime#toLocal}이 같은 순간의 Asia/Seoul 시각으로 환산한다.</li>
 *   <li>오프셋이 없으면 <b>Asia/Seoul(+09:00)로 간주</b>해 받아들이고 <b>경고 로그</b>를 남긴다.
 *       조용히 넘어가지 않는 이유는, 발행 측이 규격을 아직 안 지키고 있다는 사실이 로그에 드러나야
 *       전환을 마칠 수 있기 때문이다.</li>
 * </ol>
 *
 * <p><b>제거 조건</b>: ROS2·Isaac·임베디드·AI 발행 측이 모두 {@code +09:00}을 붙여 보내는 것이 확인되고
 * 아래 경고 로그가 더 이상 나오지 않으면 이 모듈을 삭제한다. 그때부터는 오프셋 없는 payload가 규격 위반으로
 * 명확히 거부된다.
 *
 * <p>{@code @JsonComponent}가 아니라 {@code @Bean}으로 등록되는 일반 모듈이라, Spring이 만드는
 * {@code ObjectMapper}에만 적용된다. 순수 단위 테스트가 직접 만든 {@code ObjectMapper}에는 적용되지
 * 않으므로, 그런 테스트는 확정 규격대로 오프셋을 붙인 payload를 쓴다.
 */
public class CommunicationTimeModule extends SimpleModule {

    private static final Logger log = LoggerFactory.getLogger(CommunicationTimeModule.class);

    public CommunicationTimeModule() {
        addDeserializer(OffsetDateTime.class, new LenientOffsetDateTimeDeserializer());
    }

    static class LenientOffsetDateTimeDeserializer extends JsonDeserializer<OffsetDateTime> {

        @Override
        public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            String value = text.trim();
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException withOffsetFailed) {
                try {
                    OffsetDateTime assumed = LocalDateTime.parse(value).atOffset(CommunicationTime.OFFSET);
                    log.warn("Received a timestamp without a UTC offset and assumed Asia/Seoul (+09:00). "
                                    + "The sender should migrate to the confirmed ISO-8601 +09:00 format: value={}",
                            value);
                    return assumed;
                } catch (DateTimeParseException plainFailed) {
                    // 두 형식 모두 아니면 원래 예외를 그대로 올려 기존 파싱 실패 처리 경로를 탄다.
                    throw withOffsetFailed;
                }
            }
        }
    }
}
