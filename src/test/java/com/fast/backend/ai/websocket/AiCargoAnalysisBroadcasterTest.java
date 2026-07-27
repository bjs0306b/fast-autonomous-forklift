package com.fast.backend.ai.websocket;

import com.fast.backend.ai.domain.AiAnalysisStatus;
import com.fast.backend.ai.dto.AiCargoAnalysisResponse;
import com.fast.backend.common.websocket.RealtimeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * "WebSocket 실패는 로그만 남기고 위로 던지지 않는다"와 destination 두 곳(전체/화물별) 전송을
 * 검증한다(VehicleWebSocketBroadcasterTest와 동일한 패턴).
 */
class AiCargoAnalysisBroadcasterTest {

    @Test
    void broadcast_withCargoId_sendsToAllAndCargoSpecificTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        AiCargoAnalysisBroadcaster broadcaster = new AiCargoAnalysisBroadcaster(template);
        AiCargoAnalysisResponse response = response("ANALYSIS-001", "CARGO-001");

        broadcaster.broadcast(response);

        verify(template).convertAndSend(eq("/topic/ai/cargo-analysis"), any(RealtimeEvent.class));
        verify(template).convertAndSend(eq("/topic/ai/cargo-analysis/CARGO-001"), any(RealtimeEvent.class));
    }

    @Test
    void broadcast_withoutCargoId_sendsOnlyToAllTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        AiCargoAnalysisBroadcaster broadcaster = new AiCargoAnalysisBroadcaster(template);
        AiCargoAnalysisResponse response = response("ANALYSIS-002", null);

        broadcaster.broadcast(response);

        verify(template, times(1)).convertAndSend(anyString(), any(Object.class));
        verify(template).convertAndSend(eq("/topic/ai/cargo-analysis"), any(RealtimeEvent.class));
    }

    @Test
    void broadcast_messagingTemplateThrows_exceptionDoesNotPropagate() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new RuntimeException("boom")).when(template).convertAndSend(anyString(), any(Object.class));
        AiCargoAnalysisBroadcaster broadcaster = new AiCargoAnalysisBroadcaster(template);

        assertThatCode(() -> broadcaster.broadcast(response("ANALYSIS-003", "CARGO-003")))
                .doesNotThrowAnyException();
    }

    private AiCargoAnalysisResponse response(String analysisId, String cargoId) {
        return new AiCargoAnalysisResponse(
                analysisId, "FORKLIFT-01", cargoId, AiAnalysisStatus.OK,
                new AiCargoAnalysisResponse.Detection(List.of()),
                null, null, null, null,
                "메시지", LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
    }
}
