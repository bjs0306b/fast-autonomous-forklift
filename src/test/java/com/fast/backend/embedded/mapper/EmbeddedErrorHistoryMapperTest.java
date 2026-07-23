package com.fast.backend.embedded.mapper;

import com.fast.backend.embedded.domain.EmbeddedErrorHistory;
import com.fast.backend.embedded.domain.EmbeddedErrorSeverity;
import com.fast.backend.embedded.domain.EmbeddedErrorSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** embedded_error_history insert/누적 조회가 실제 H2(MySQL 호환 모드)에서 동작하는지 검증한다(prompt29.md 17장). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmbeddedErrorHistoryMapperTest {

    @Autowired
    private EmbeddedErrorHistoryMapper mapper;

    @Test
    void insert_generatesId() {
        EmbeddedErrorHistory history = newHistory("REAL01", "E001", EmbeddedErrorSeverity.WARNING);

        mapper.insert(history);

        assertThat(history.getId()).isNotNull();
    }

    @Test
    void findRecentByForkliftId_ordersByOccurredAtDescendingAndRespectsLimit() {
        LocalDateTime base = LocalDateTime.now().minusMinutes(10);
        for (int i = 0; i < 3; i++) {
            EmbeddedErrorHistory history = newHistory("REAL02", "E00" + i, EmbeddedErrorSeverity.WARNING);
            history.setOccurredAt(base.plusMinutes(i));
            history.setReceivedAt(base.plusMinutes(i));
            history.setCreatedAt(base.plusMinutes(i));
            mapper.insert(history);
        }

        List<EmbeddedErrorHistory> recent = mapper.findRecentByForkliftId("REAL02", 2);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).getErrorCode()).isEqualTo("E002");
        assertThat(recent.get(1).getErrorCode()).isEqualTo("E001");
    }

    @Test
    void findRecentByForkliftId_separatesDifferentForklifts() {
        mapper.insert(newHistory("REAL03", "E001", EmbeddedErrorSeverity.CRITICAL));
        mapper.insert(newHistory("REAL04", "E002", EmbeddedErrorSeverity.CRITICAL));

        List<EmbeddedErrorHistory> recent = mapper.findRecentByForkliftId("REAL03", 50);

        assertThat(recent).extracting(EmbeddedErrorHistory::getForkliftId).containsOnly("REAL03");
    }

    @Test
    void insert_criticalSeverityAndErrorSourceEnum_roundTrips() {
        EmbeddedErrorHistory history = newHistory("REAL05", "E999", EmbeddedErrorSeverity.CRITICAL);
        history.setErrorSource(EmbeddedErrorSource.LIMIT_SWITCH);
        mapper.insert(history);

        EmbeddedErrorHistory found = mapper.findRecentByForkliftId("REAL05", 1).get(0);
        assertThat(found.getSeverity()).isEqualTo(EmbeddedErrorSeverity.CRITICAL);
        assertThat(found.getErrorSource()).isEqualTo(EmbeddedErrorSource.LIMIT_SWITCH);
    }

    private EmbeddedErrorHistory newHistory(String forkliftId, String errorCode, EmbeddedErrorSeverity severity) {
        LocalDateTime now = LocalDateTime.now();
        EmbeddedErrorHistory history = new EmbeddedErrorHistory();
        history.setForkliftId(forkliftId);
        history.setErrorCode(errorCode);
        history.setErrorSource(EmbeddedErrorSource.DRIVE);
        history.setSeverity(severity);
        history.setMessage("테스트 오류");
        history.setOccurredAt(now);
        history.setReceivedAt(now);
        history.setCreatedAt(now);
        return history;
    }
}
